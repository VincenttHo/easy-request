package com.easyrequest.model;

/** Serializable tree placement, independent of PSI and of user-maintained request values. */
public final class CatalogLocation {
    public String groupId = "";
    public String groupName = "";
    public String groupPath = "";
    public String controllerId = "";
    public String controllerTitle = "";
    public String className = "";
    public String filePath = "";

    public CatalogLocation copy() {
        CatalogLocation copy = new CatalogLocation();
        copy.groupId = groupId; copy.groupName = groupName; copy.groupPath = groupPath;
        copy.controllerId = controllerId; copy.controllerTitle = controllerTitle;
        copy.className = className; copy.filePath = filePath;
        return copy;
    }
}
