package com.easyrequest.model;

import java.util.UUID;

public final class FolderData {
    public String id = UUID.randomUUID().toString();
    public String parentId = "";
    public String name = "";
}
