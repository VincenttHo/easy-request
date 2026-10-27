package com.easyrequest.model;

/** A request draft and the last saved version used to track unsaved edits. */
public final class RequestTabState {
    public RequestData request = new RequestData();
    public RequestData baseline;
    /** Read compatibility for legacy drafts; catalog records are now identified by request.id. */
    public String endpointKey = "";
    public boolean preview;
}
