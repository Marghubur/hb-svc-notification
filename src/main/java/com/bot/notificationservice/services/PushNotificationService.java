package com.bot.notificationservice.services;

import com.bot.notificationservice.model.NotificationMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fierhub.service.FierhubService;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class PushNotificationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PushNotificationService.class);
    public static final String DEFAULT_APP = FirebaseApp.DEFAULT_APP_NAME;

    private final Map<String, FirebaseMessaging> messagingMap = new ConcurrentHashMap<>();

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    @Qualifier("notificationExecutor")
    private Executor notificationExecutor;

    @Autowired
    FierhubService fierhubService;

    @PostConstruct
    private void initializeDefaultFirebase() {
        try {
            LOGGER.info("Initializing default Firebase application at startup...");
            getFirebaseMessaging(DEFAULT_APP);
        } catch (Exception ex) {
            LOGGER.warn("Default Firebase initialization at startup deferred: {}", ex.getMessage());
        }
    }

    /**
     * Retrieves an existing FirebaseMessaging instance for the target app,
     * or initializes a new named FirebaseApp dynamically and caches it.
     */
    public FirebaseMessaging getFirebaseMessaging(String appName) {
        String targetApp = (appName == null || appName.isBlank()) ? DEFAULT_APP : appName.trim();
        FirebaseMessaging messaging = messagingMap.get(targetApp);
        if (messaging != null) {
            return messaging;
        }

        synchronized (this) {
            messaging = messagingMap.get(targetApp);
            if (messaging != null) {
                return messaging;
            }

            messaging = initFirebaseMessaging(targetApp);
            messagingMap.put(targetApp, messaging);
            return messaging;
        }
    }

    private FirebaseMessaging initFirebaseMessaging(String appName) {
        for (FirebaseApp existingApp : FirebaseApp.getApps()) {
            if (existingApp.getName().equals(appName)) {
                LOGGER.info("Found existing FirebaseApp instance for [{}]. Reusing.", appName);
                return FirebaseMessaging.getInstance(existingApp);
            }
        }

        try {
            InputStream serviceAccountStream = loadServiceAccountStream(appName);
            if (serviceAccountStream == null) {
                if (!DEFAULT_APP.equals(appName)) {
                    LOGGER.warn("No credentials configured for application [{}]. Falling back to DEFAULT FirebaseApp.", appName);
                    return getFirebaseMessaging(DEFAULT_APP);
                }
                throw new IllegalStateException("Credentials missing for application: " + appName);
            }

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccountStream))
                    .build();

            FirebaseApp app;
            if (DEFAULT_APP.equals(appName)) {
                app = FirebaseApp.initializeApp(options);
            } else {
                app = FirebaseApp.initializeApp(options, appName);
            }

            LOGGER.info("Firebase application [{}] initialized successfully.", appName);
            return FirebaseMessaging.getInstance(app);
        } catch (Exception ex) {
            LOGGER.error("Failed to initialize Firebase for application [{}]: {}", appName, ex.getMessage());
            throw new RuntimeException("Failed to initialize Firebase for application: " + appName, ex);
        }
    }

    private InputStream loadServiceAccountStream(String appName) throws Exception {
        String rawJson = getServiceAccountJson(appName);
        if (rawJson != null && !rawJson.isBlank()) {
            LOGGER.info("Loaded raw JSON service account for app [{}] via FierhubService.", appName);
            return new ByteArrayInputStream(rawJson.getBytes(StandardCharsets.UTF_8));
        }

        Map<String, Object> serviceAccountMap = new HashMap<>();
        serviceAccountMap.put("type", getServiceAccountKey(appName, "type", "service_account"));
        serviceAccountMap.put("project_id", getServiceAccountKey(appName, "project_id", null));
        serviceAccountMap.put("private_key_id", getServiceAccountKey(appName, "private_key_id", null));
        serviceAccountMap.put("private_key", getServiceAccountKey(appName, "private_key", null));
        serviceAccountMap.put("client_email", getServiceAccountKey(appName, "client_email", null));
        serviceAccountMap.put("client_id", getServiceAccountKey(appName, "client_id", null));
        serviceAccountMap.put("auth_uri", getServiceAccountKey(appName, "auth_uri", "https://accounts.google.com/o/oauth2/auth"));
        serviceAccountMap.put("token_uri", getServiceAccountKey(appName, "token_uri", "https://oauth2.googleapis.com/token"));
        serviceAccountMap.put("auth_provider_x509_cert_url", getServiceAccountKey(appName, "auth_provider_x509_cert_url", "https://www.googleapis.com/oauth2/v1/certs"));
        serviceAccountMap.put("client_x509_cert_url", getServiceAccountKey(appName, "client_x509_cert_url", null));
        serviceAccountMap.put("universe_domain", getServiceAccountKey(appName, "universe_domain", "googleapis.com"));

        String privateKey = (String) serviceAccountMap.get("private_key");
        if (privateKey != null && !privateKey.trim().isEmpty()) {
            if (privateKey.contains("\\n")) {
                serviceAccountMap.put("private_key", privateKey.replace("\\n", "\n"));
            }
            byte[] jsonBytes = objectMapper.writeValueAsBytes(serviceAccountMap);
            LOGGER.info("Loaded Firebase credentials for app [{}] via FierhubService configuration.", appName);
            return new ByteArrayInputStream(jsonBytes);
        }

        if (DEFAULT_APP.equals(appName)) {
            LOGGER.warn("Firebase private_key not found in FierhubService for [DEFAULT]. Falling back to serviceAccountKey.json");
            return PushNotificationService.class.getClassLoader().getResourceAsStream("serviceAccountKey.json");
        }

        return null;
    }

    private String getServiceAccountJson(String appName) {
        if (!DEFAULT_APP.equals(appName)) {
            String json = fierhubService.getConfiguration(appName + ".firebase.service-account", String.class);
            if (json == null || json.isBlank()) {
                json = fierhubService.getConfiguration("firebase." + appName + ".service-account", String.class);
            }
            if (json == null || json.isBlank()) {
                json = fierhubService.getConfiguration(appName + ".service-account", String.class);
            }
            if (json != null && !json.isBlank()) {
                return json;
            }
        }
        String json = fierhubService.getConfiguration("firebase.service-account", String.class);
        if (json == null || json.isBlank()) {
            json = fierhubService.getConfiguration("service-account", String.class);
        }
        return json;
    }

    private String getServiceAccountKey(String appName, String key, String defaultValue) {
        String value = null;
        if (!DEFAULT_APP.equals(appName)) {
            value = fierhubService.getConfiguration(appName + "." + key, String.class);
            if (value == null || value.trim().isEmpty()) {
                throw new InvalidParameterException("Service account detail not found for: " + appName + "." + key);
            }
        }

        return value;
    }

    public String resolveAppId(NotificationMessage model) {
        if (model == null) {
            return DEFAULT_APP;
        }
        if (model.getAppId() != null && !model.getAppId().isBlank()) {
            return model.getAppId().trim();
        }
        if (model.getTenantId() != null && !model.getTenantId().isBlank()) {
            return model.getTenantId().trim();
        }
        if (model.getCompanyId() != null && !model.getCompanyId().isBlank()) {
            return model.getCompanyId().trim();
        }
        if (model.getData() != null && !model.getData().isEmpty()) {
            String candidate = model.getData().get("appId");
            if (candidate == null || candidate.isBlank()) {
                candidate = model.getData().get("applicationId");
            }
            if (candidate == null || candidate.isBlank()) {
                candidate = model.getData().get("tenantId");
            }
            if (candidate == null || candidate.isBlank()) {
                candidate = model.getData().get("companyId");
            }
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return DEFAULT_APP;
    }

    public String processNotification(String kafkaMessage) throws Exception {
        LOGGER.info("Processing consumed Kafka message: {}", kafkaMessage);
        if (kafkaMessage == null || kafkaMessage.isBlank()) {
            LOGGER.warn("Received empty or null Kafka message, skipping notification.");
            return "skipped_empty_message";
        }

        NotificationMessage model;
        try {
            if (kafkaMessage.trim().startsWith("[")) {
                List<NotificationMessage> list = objectMapper.readValue(
                        kafkaMessage,
                        new TypeReference<List<NotificationMessage>>() {
                        }
                );
                return sendPushNotificationBatch(list);
            } else if (kafkaMessage.trim().startsWith("{")) {
                model = objectMapper.readValue(kafkaMessage, NotificationMessage.class);
            } else {
                model = new NotificationMessage();
                model.setTitle("New Job Alert");
                model.setDescription(kafkaMessage);
                model.setTopic("all");
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to parse message as JSON: {}. Using fallback model.", e.getMessage());
            model = new NotificationMessage();
            model.setTitle("New Job Alert");
            model.setDescription(kafkaMessage);
            model.setTopic("all");
        }

        return sendPushNotification(model);
    }

    public String sendPushNotificationBatch(List<NotificationMessage> list) {
        if (list == null || list.isEmpty()) {
            return "empty_batch";
        }

        LOGGER.info("Processing batch of {} notifications in parallel", list.size());
        Executor executor = notificationExecutor != null ? notificationExecutor : ForkJoinPool.commonPool();

        // Group messages by target application / tenant
        Map<String, List<NotificationMessage>> groupedByApp = list.stream()
                .collect(Collectors.groupingBy(this::resolveAppId));

        int batchSize = 500;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<String, List<NotificationMessage>> entry : groupedByApp.entrySet()) {
            String appId = entry.getKey();
            List<NotificationMessage> appList = entry.getValue();
            FirebaseMessaging messaging = getFirebaseMessaging(appId);

            for (int i = 0; i < appList.size(); i += batchSize) {
                List<NotificationMessage> subBatch = appList.subList(i, Math.min(i + batchSize, appList.size()));
                futures.add(CompletableFuture.runAsync(() -> {
                    List<Message> firebaseMessages = new ArrayList<>();
                    for (NotificationMessage item : subBatch) {
                        try {
                            Message msg = createFirebaseMessage(item);
                            if (msg != null) {
                                firebaseMessages.add(msg);
                            }
                        } catch (Exception ex) {
                            failureCount.incrementAndGet();
                            LOGGER.error("Failed to build Firebase message for follower {}: {}", item.getFollowerId(), ex.getMessage());
                        }
                    }

                    if (!firebaseMessages.isEmpty()) {
                        try {
                            BatchResponse response = messaging.sendEach(firebaseMessages);
                            successCount.addAndGet(response.getSuccessCount());
                            failureCount.addAndGet(response.getFailureCount());
                            if (response.getFailureCount() > 0) {
                                LOGGER.warn("Firebase batch for app [{}] finished with {} successes and {} failures out of {} messages",
                                        appId, response.getSuccessCount(), response.getFailureCount(), firebaseMessages.size());
                            }
                        } catch (Exception ex) {
                            failureCount.addAndGet(firebaseMessages.size());
                            LOGGER.error("Failed to send Firebase batch of {} messages for app [{}]: {}",
                                    firebaseMessages.size(), appId, ex.getMessage(), ex);
                        }
                    }
                }, executor));
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        LOGGER.info("Parallel notification batch completed across {} app(s): {}/{} sent successfully ({} failed)",
                groupedByApp.size(), successCount.get(), list.size(), failureCount.get());

        return String.format("Processed batch of %d notifications across %d app(s): %d succeeded, %d failed",
                list.size(), groupedByApp.size(), successCount.get(), failureCount.get());
    }

    public Message createFirebaseMessage(NotificationMessage notificationMessage) {
        if (notificationMessage == null) {
            return null;
        }

        String title = notificationMessage.getTitle();
        if (title == null || title.isBlank()) {
            title = "New Job Alert";
        }

        String body = resolveBody(notificationMessage);
        if (body == null || body.isBlank()) {
            body = "A new job post update has been published.";
        }

        Notification.Builder notificationBuilder = Notification.builder()
                .setTitle(title)
                .setBody(body);

        if (notificationMessage.getImageUrl() != null && !notificationMessage.getImageUrl().isBlank()) {
            notificationBuilder.setImage("https://api.hiringbell.com/" + notificationMessage.getImageUrl());
        } else {
            notificationBuilder.setImage("https://api.hiringbell.com/hiringbell_logo.png");
        }

        Notification notification = notificationBuilder.build();

        long targetPostId = notificationMessage.getJobPostId() > 0 ? notificationMessage.getJobPostId() : notificationMessage.getPostId();
        String notificationType = notificationMessage.getNotificationType() != null && !notificationMessage.getNotificationType().isBlank()
                ? notificationMessage.getNotificationType()
                : "JOB_POST";

        Message.Builder messageBuilder = Message.builder()
                .setNotification(notification)
                .putData("notificationType", notificationType)
                .putData("followedId", String.valueOf(notificationMessage.getFollowedId()))
                .putData("jobPostId", String.valueOf(targetPostId))
                .putData("followerId", String.valueOf(notificationMessage.getFollowerId()))
                .putData("fullName", notificationMessage.getFullName() != null ? notificationMessage.getFullName() : "")
                .putData("description", body)
                .putData("imageUrl", notificationMessage.getImageUrl() != null ? notificationMessage.getImageUrl() : "")
                .putData("time", String.valueOf(System.currentTimeMillis()));

        if (notificationMessage.getData() != null && !notificationMessage.getData().isEmpty()) {
            notificationMessage.getData().forEach((k, v) -> {
                if (k != null && v != null) {
                    messageBuilder.putData(k, v);
                }
            });
        }

        String deviceToken = resolveDeviceToken(notificationMessage);
        if (deviceToken != null && !deviceToken.isBlank()) {
            messageBuilder.setToken(deviceToken);
            LOGGER.info("Targeting push notification to device token: {}", deviceToken);
        } else if (notificationMessage.getTopic() != null && !notificationMessage.getTopic().isBlank()) {
            messageBuilder.setTopic(notificationMessage.getTopic());
            LOGGER.info("Targeting push notification to topic: {}", notificationMessage.getTopic());
        } else {
            messageBuilder.setTopic("all");
            LOGGER.info("No specific device token found. Targeting push notification to topic 'all'");
        }

        return messageBuilder.build();
    }

    public String sendPushNotification(NotificationMessage notificationMessage) throws Exception {
        if (notificationMessage == null) {
            throw new IllegalArgumentException("Notification model cannot be null");
        }

        String targetApp = resolveAppId(notificationMessage);
        FirebaseMessaging messaging = getFirebaseMessaging(targetApp);

        Message message = createFirebaseMessage(notificationMessage);
        String response = messaging.send(message);
        LOGGER.info("Successfully sent push notification via Firebase [app: {}]: {}", targetApp, response);
        return response;
    }

    private String resolveDeviceToken(NotificationMessage model) {
        if (model.getDeviceId() != null && !model.getDeviceId().isBlank()) {
            return model.getDeviceId();
        }
        if (model.getToken() != null && !model.getToken().isBlank()) {
            return model.getToken();
        }
        return null;
    }

    private String resolveBody(NotificationMessage model) {
        if (model.getDescription() != null && !model.getDescription().isBlank()) {
            return model.getDescription();
        }
        if (model.getBody() != null && !model.getBody().isBlank()) {
            return model.getBody();
        }
        if (model.getMessage() != null && !model.getMessage().isBlank()) {
            return model.getMessage();
        }
        return null;
    }
}
