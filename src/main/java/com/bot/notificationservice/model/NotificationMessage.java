package com.bot.notificationservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotificationMessage {
    @JsonProperty("appId")
    private String appId;

    @JsonProperty("tenantId")
    private String tenantId;

    @JsonProperty("companyId")
    private String companyId;

    @JsonProperty("rowIndex")
    private long rowIndex;

    @JsonProperty("followerId")
    private long followerId;

    @JsonProperty("followedId")
    private long followedId;

    @JsonProperty("jobPostId")
    private long jobPostId;

    @JsonProperty("postId")
    private long postId;

    @JsonProperty("userId")
    private long userId;

    @JsonProperty("title")
    private String title;

    @JsonProperty("description")
    private String description;

    @JsonProperty("message")
    private String message;

    @JsonProperty("body")
    private String body;

    @JsonProperty("imageUrl")
    private String imageUrl;

    @JsonProperty("token")
    private String token;

    @JsonProperty("deviceId")
    private String deviceId;

    @JsonProperty("topic")
    private String topic;

    @JsonProperty("notificationType")
    private String notificationType;

    @JsonProperty("fullName")
    private String fullName;

    @JsonProperty("email")
    private String email;

    @JsonProperty("data")
    private Map<String, String> data;
}
