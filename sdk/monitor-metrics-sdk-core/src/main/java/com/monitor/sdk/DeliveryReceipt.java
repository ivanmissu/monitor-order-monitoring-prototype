package com.monitor.sdk;

/** Monitor ingest 接口对一批事件的终态回执摘要。 */
public final class DeliveryReceipt {

    private final int httpStatus;
    private final int accepted;
    private final int duplicated;
    private final int rejected;
    private final String responseBody;

    public DeliveryReceipt(int httpStatus, int accepted, int duplicated, int rejected, String responseBody) {
        this.httpStatus = httpStatus;
        this.accepted = accepted;
        this.duplicated = duplicated;
        this.rejected = rejected;
        this.responseBody = responseBody;
    }

    public int getHttpStatus() { return httpStatus; }
    public int getAccepted() { return accepted; }
    public int getDuplicated() { return duplicated; }
    public int getRejected() { return rejected; }
    public String getResponseBody() { return responseBody; }
}
