package org.sagebionetworks.bridge.models;

import org.sagebionetworks.bridge.json.BridgeTypeName;

import com.fasterxml.jackson.databind.JsonNode;

@BridgeTypeName("BackfillRecord")
public interface BackfillRecord extends BridgeEntity {

    String getTaskId();

    long getTimestamp();

    JsonNode toJsonNode();
}
