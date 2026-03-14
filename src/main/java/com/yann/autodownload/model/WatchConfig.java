package com.yann.autodownload.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class WatchConfig {

    @JsonProperty("mirrorGroupId")
    private long mirrorGroupId;

    @JsonProperty("ownerUserId")
    private long ownerUserId;

    @JsonProperty("entries")
    private List<WatchEntry> entries = new ArrayList<>();
}
