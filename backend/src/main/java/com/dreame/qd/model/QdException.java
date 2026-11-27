package com.dreame.qd.model;

/** 无法继续的失败（04 1.2）。message 面向运维负责人，不得包含密码、令牌等敏感信息。 */
public class QdException extends RuntimeException {
    private final ErrorCode errorCode;
    private final boolean retryable;

    public QdException(ErrorCode errorCode, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public QdException(ErrorCode errorCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public ErrorCode errorCode() { return errorCode; }

    public boolean retryable() { return retryable; }

    /** 形如 "QD-JIRA-001：说明" */
    public String summary() { return errorCode.code() + "：" + getMessage(); }
}
