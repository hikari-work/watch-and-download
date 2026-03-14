package com.yann.autodownload.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WatchEntry {

    @JsonProperty("sourceGroupId")
    private long sourceGroupId;

    @JsonProperty("sourceGroupName")
    private String sourceGroupName;

    @JsonProperty("topicId")
    private long topicId;

    @JsonProperty("contentTypes")
    private Set<ContentType> contentTypes = new HashSet<>();
}
