package com.monitor.sdk;

/** 不会再重试的一批事件的失败原因。 */
public final class DeliveryFailure {

    private final int httpStatus;
    private final String message;
    private final Throwable cause;

    public DeliveryFailure(int httpStatus, String message, Throwable cause) {
        this.httpStatus = httpStatus;
        this.message = message;
        this.cause = cause;
    }

    public int getHttpStatus() { return httpStatus; }
    public String getMessage() { return message; }
    public Throwable getCause() { return cause; }
}
