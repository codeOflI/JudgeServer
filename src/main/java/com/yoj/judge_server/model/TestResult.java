package com.yoj.judge_server.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 
 * @author nicolas
 *	one test result 
 */
@Setter
@Getter
@ToString
public class TestResult {
	private Integer timeUsed;
	private Integer memoryUsed;
	//JudgeResult
	private Integer result;
}
