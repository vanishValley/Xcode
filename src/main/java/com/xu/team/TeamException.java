package com.xu.team;

/** 对模型可解释的协议错误，避免让参数错误终止整个团队。 */
public final class TeamException extends RuntimeException {
    private final String code;
    public TeamException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
