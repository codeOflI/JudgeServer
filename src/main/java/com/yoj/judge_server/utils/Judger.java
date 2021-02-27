package com.yoj.judge_server.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.yoj.judge_server.model.ExecuteMessage;
import com.yoj.judge_server.model.JudgeSource;
import com.yoj.judge_server.model.Solution;
import com.yoj.judge_server.model.TestResult;
import com.yoj.judge_server.model.properties.JudgeProperties;
import com.yoj.judge_server.enums.JudgeResult;
import com.yoj.judge_server.enums.Language;
import com.yoj.judge_server.threads.JudgeThreadPoolManager;
import com.yoj.judge_server.utils.ExecutorUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class Judger {
    protected final String[] fileNames = {"main.c", "main.cpp", "Main.java", "main.py"};

    @Autowired
    private JudgeProperties judgeProperties;
    @Autowired
    private ExecutorUtil executor;

    @Autowired
    JudgeThreadPoolManager judgeThreadPoolManager;

    /**
     * return update solution by judgeSource
     *
     * @param judgeSource
     * @return update solution
     */
    public Solution judge(JudgeSource judgeSource) {
        // linux path,tmp directory store temporary files
        //uuid 重复的可能性很低
        String dirPath = UUID.randomUUID().toString();
        String linuxPath = judgeProperties.getSolutionFilePath() + "/" + dirPath;
        Solution solution = new Solution();
        // attribute mapping
        BeanUtils.copyProperties(judgeSource, solution);
        try {
            createSolutionFile(solution, linuxPath);
        } catch (Exception e) {
            e.printStackTrace();
            solution.setErrorMessage("system exception:create file fail");
            solution.setResult(JudgeResult.WAIT_REJUDGE.ordinal());
            log.info("JudgeUtil : create file fail");
            deleteSolutionFile(linuxPath);
            return solution;
        }

        // compile the source
        String message = compile(solution.getLanguage(), linuxPath);
        if (message != null) {
            solution.setResult(JudgeResult.COMPILE_ERROR.ordinal());
            solution.setErrorMessage(message);
            log.warn("JudgeUtil : compile error");
            log.warn("JudgeUtil :  " + message);
            deleteSolutionFile(linuxPath);
            return solution;
        }
        // chmod -R 755 path
        executor.execute("chmod -R 755 " + linuxPath);
        // judge
        String process = process(solution.getLanguage(), linuxPath);
//		String judge_data = PropertiesUtil.StringValue("judge_data") + "/" + task.getProblemId();
        String judgeDataPath = judgeProperties.getProblemFilePath() + "/" + solution.getProblemId();
        String judgePyPath = judgeProperties.getJudgeScriptPath();
        int memoryLimit = judgeSource.getMemoryLimit() * 1024;
        //#服务器内存不够分配。。。。。给大点，和小一点都行????
        if (solution.getLanguage() == Language.JAVA.ordinal()) {
            memoryLimit = 2000000;
        }
        String cmd = "python " + judgePyPath + " " + process + " " + judgeDataPath + " "
                + linuxPath + " " + judgeSource.getTimeLimit() + " " + memoryLimit;
        parseToResult(cmd, solution);
        deleteSolutionFile(linuxPath);
        return solution;
    }


    public void createSolutionFile(Solution solution, String linuxPath) throws Exception {
        File file = new File(linuxPath);
        file.mkdirs();
        FileUtils.write(new File(linuxPath + "/" + this.fileNames[solution.getLanguage()]),
                solution.getCode(), "utf-8");
    }

    public void deleteSolutionFile(String linuxPath) {
        executor.execute("rm -rf " + linuxPath);
    }


    private String compile(int compilerId, String path) {
        /**
         * '0': 'gcc','1' 'g++', '2': 'java', '3': 'python', '4': 'pascal', -o outfile
         */
        String cmd = "";
        switch (compilerId) {
            case 0:
//                cmd = "gcc " + path + "/main.c -o " + path + "/main";
                cmd = "gcc " + path + "/main.c -fno-asm -Wall -lm --static -std=c99 -DONLINE_JUDGE -o " + path + "/main";
//                gcc test.c   -fno-asm -Wall -lm --static -std=c99 -DONLINE_JUDGE -o main
                break;
            case 1:
//                cmd = "g++ " + path + "/main.cpp -o " + path + "/main";
                cmd = "g++ " + path + "/main.cpp  -fno-asm -Wall -lm --static -std=c++11 -DONLINE_JUDGE -o " + path + "/main";
                break;
            case 2:
                cmd = "javac -J-Xms32m -J-Xmx256m " + path + "/Main.java";
//                javac -J-Xms32m -J-Xmx256m Main.java
                break;
            case 3:
                cmd = "python3 -m py_compile " + path + "/main.py";
                break;
//            case 4:
//                cmd = "fpc " + path + "/main.pas -O2 -Co -Ct -Ci";
//                break;
        }
        return executor.execute(cmd).getError();
    }

    private String process(int language, String path) {
        switch (language) {
            case 0:
                return path + "/main";
            case 1:
                return path + "/main";
            case 2:
                return "javalmz-classpathlmz" + path + "lmzMain";
            case 3:
//                                #python编译生成对应的版本文件名字
//                    python_cacheName=main.cpython-36.pyc
                return "python3lmz" + path + "/__pycache__/" + "main.cpython-36.pyc";
//		case 5:
//			return path + "/main";
        }
        return null;
    }

    private void parseToResult(String cmd, Solution solution) {
        ExecuteMessage exec = executor.execute(cmd);
        if (exec.getError() != null) {
            solution.setErrorMessage(exec.getError());
            solution.setResult(JudgeResult.WAIT_REJUDGE.ordinal());
            log.error("=====error====" + exec.getStdout() + "    :" + exec.getError());
        } else {
            try {
                log.info("=====stdout====" + exec.getStdout());
                String jsonFormat = "[" + exec.getStdout() + "]";
                List<TestResult> outs = JSONArray.parseArray(jsonFormat, TestResult.class);
//                String testResult = JSONArray.toJSON(outs).toString();
                //必须要保存标准格式的json数据
                // remove last because it's a information that compares with all test results
                int lastIdx = outs.size() - 1;
                solution.setTestResult(JSON.toJSON(outs.subList(0, lastIdx)).toString());
                solution.setRuntime(outs.get(lastIdx).getTimeUsed());
                solution.setMemory(outs.get(lastIdx).getMemoryUsed());
                solution.setResult(outs.get(lastIdx).getResult());
            } catch (Exception e) {
                solution.setResult(JudgeResult.WAIT_REJUDGE.ordinal());
                e.printStackTrace();
            }
        }
    }
}
