import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.ScramCredentialInfo;
import org.apache.kafka.clients.admin.ScramMechanism;
import org.apache.kafka.clients.admin.UserScramCredentialAlteration;
import org.apache.kafka.clients.admin.UserScramCredentialUpsertion;
import org.apache.kafka.clients.admin.UserScramCredentialsDescription;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * kafka-init (docker-compose) và Job kafka-topics-init (deploy/k8s): đưa broker về trạng thái mong
 * muốn của {@code topics.txt} và {@code acls.txt} (mesh ADR-002, ADR-003). Chạy bằng source launcher
 * của JDK trong image cp-kafka ({@code infra/kafka/init.sh}), một JVM cho mọi thao tác: mỗi lệnh CLI
 * kafka-topics/kafka-configs/kafka-acls là một JVM riêng, hơn 100 lệnh mất tới bảy phút.
 *
 * <ul>
 *   <li>Topic: tạo topic còn thiếu; không bao giờ sửa topic đã có.</li>
 *   <li>User: tạo credential SCRAM-SHA-512 cho principal chưa có, mật khẩu từ
 *       {@code SCRAM_PASSWORD_<PRINCIPAL>}; {@code SCRAM_RESET=true} đặt lại mọi mật khẩu.</li>
 *   <li>ACL: thêm ACL ALLOW còn thiếu, xoá ACL ALLOW host {@code *} không còn trong file (kể cả của
 *       principal đã bỏ). ACL DENY hoặc theo host cụ thể không bị đụng.</li>
 * </ul>
 * Exit khác 0 khi một bước thất bại, để client phụ thuộc không khởi động trên broker thiếu topic/quyền.
 */
public class KafkaInit {

    private static final long CALL_TIMEOUT_SECONDS = 60;

    public static void main(String[] args) throws Exception {
        String bootstrap = env("BOOTSTRAP_SERVER", "kafka:9092");
        Path topicsFile = Path.of(env("TOPICS_FILE", "/scripts/topics.txt"));
        Path aclsFile = Path.of(env("ACLS_FILE", "/scripts/acls.txt"));
        long deadline = System.currentTimeMillis() + Long.parseLong(env("TIMEOUT_SECONDS", "300")) * 1000;

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "15000");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "30000");
        String adminUser = System.getenv("SCRAM_ADMIN_USERNAME");
        if (adminUser != null && !adminUser.isBlank()) {
            props.put(AdminClientConfig.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT");
            props.put(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512");
            props.put(SaslConfigs.SASL_JAAS_CONFIG, jaas(adminUser, required("SCRAM_ADMIN_PASSWORD")));
        }

        try (Admin admin = Admin.create(props)) {
            Set<String> existingTopics = waitForBroker(admin, bootstrap, deadline);
            syncTopics(admin, topicsFile, existingTopics);
            if (Files.exists(aclsFile)) {
                List<AclBinding> desired = desiredAcls(aclsFile);
                syncUsers(admin, desired);
                syncAcls(admin, desired);
            }
        }
    }

    private static Set<String> waitForBroker(Admin admin, String bootstrap, long deadline) throws InterruptedException {
        while (true) {
            try {
                return admin.listTopics().names().get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (System.currentTimeMillis() >= deadline) {
                    fail("broker " + bootstrap + " không sẵn sàng: " + cause);
                }
                log("chờ broker " + bootstrap + ": " + cause.getClass().getSimpleName());
                Thread.sleep(3000);
            }
        }
    }

    /** Dòng: tên  số-partition  [cấu hình a=b,c=d]. */
    private static void syncTopics(Admin admin, Path file, Set<String> existing) throws Exception {
        List<NewTopic> missing = new ArrayList<>();
        int kept = 0;
        for (String[] cols : rows(file)) {
            if (existing.contains(cols[0])) {
                kept++;
                continue;
            }
            NewTopic topic = new NewTopic(cols[0], cols.length > 1 ? Integer.parseInt(cols[1]) : 1, (short) 1);
            if (cols.length > 2) {
                Map<String, String> configs = new HashMap<>();
                for (String pair : cols[2].split(",")) {
                    String[] kv = pair.split("=", 2);
                    configs.put(kv[0], kv[1]);
                }
                topic.configs(configs);
            }
            missing.add(topic);
        }
        if (!missing.isEmpty()) {
            for (Map.Entry<String, org.apache.kafka.common.KafkaFuture<Void>> result
                    : admin.createTopics(missing).values().entrySet()) {
                try {
                    result.getValue().get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    log("đã tạo topic " + result.getKey());
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw e;
                    }
                }
            }
        }
        log("topic: tạo " + missing.size() + ", đã có " + kept);
    }

    /** Dòng: principal  tài-nguyên  thao-tác[,thao-tác]  tên... */
    private static List<AclBinding> desiredAcls(Path file) throws Exception {
        Set<AclBinding> bindings = new LinkedHashSet<>();
        for (String[] cols : rows(file)) {
            if (cols.length < 3) {
                fail("dòng ACL thiếu cột: " + String.join(" ", cols));
            }
            String principal = "User:" + cols[0];
            ResourceType type;
            PatternType pattern = PatternType.LITERAL;
            switch (cols[1]) {
                case "topic": type = ResourceType.TOPIC; break;
                case "topic-prefixed": type = ResourceType.TOPIC; pattern = PatternType.PREFIXED; break;
                case "group": type = ResourceType.GROUP; break;
                case "group-prefixed": type = ResourceType.GROUP; pattern = PatternType.PREFIXED; break;
                case "cluster": type = ResourceType.CLUSTER; break;
                default: fail("tài nguyên lạ '" + cols[1] + "' của " + cols[0]); return null;
            }
            List<String> names = type == ResourceType.CLUSTER
                    ? List.of("kafka-cluster") : Arrays.asList(cols).subList(3, cols.length);
            if (names.isEmpty()) {
                fail("dòng ACL không có tên tài nguyên: " + String.join(" ", cols));
            }
            for (String op : cols[2].split(",")) {
                AclOperation operation = operation(op);
                for (String name : names) {
                    bindings.add(new AclBinding(new ResourcePattern(type, name, pattern),
                            new AccessControlEntry(principal, "*", operation, AclPermissionType.ALLOW)));
                }
            }
        }
        return new ArrayList<>(bindings);
    }

    private static void syncUsers(Admin admin, List<AclBinding> desired) throws Exception {
        boolean reset = "true".equalsIgnoreCase(env("SCRAM_RESET", "false"));
        int iterations = Integer.parseInt(env("SCRAM_ITERATIONS", "8192"));
        Map<String, UserScramCredentialsDescription> users =
                admin.describeUserScramCredentials().all().get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Set<String> principals = desired.stream()
                .map(b -> b.entry().principal().substring("User:".length()))
                .collect(Collectors.toCollection(TreeSet::new));
        List<UserScramCredentialAlteration> upserts = new ArrayList<>();
        for (String principal : principals) {
            String variable = "SCRAM_PASSWORD_" + principal.toUpperCase(Locale.ROOT).replace('-', '_');
            String password = System.getenv(variable);
            if (password == null || password.isEmpty()) {
                fail("thiếu " + variable + " cho principal " + principal);
            }
            UserScramCredentialsDescription current = users.get(principal);
            boolean present = current != null && current.credentialInfos().stream()
                    .anyMatch(info -> info.mechanism() == ScramMechanism.SCRAM_SHA_512);
            if (reset || !present) {
                upserts.add(new UserScramCredentialUpsertion(principal,
                        new ScramCredentialInfo(ScramMechanism.SCRAM_SHA_512, iterations), password));
                log("đặt credential SCRAM-SHA-512 cho " + principal);
            }
        }
        if (!upserts.isEmpty()) {
            admin.alterUserScramCredentials(upserts).all().get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        log("user: " + principals.size() + " principal, đặt " + upserts.size() + " credential");
    }

    private static void syncAcls(Admin admin, List<AclBinding> desired) throws Exception {
        Set<AclBinding> existing = admin.describeAcls(AclBindingFilter.ANY).values()
                .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                .filter(b -> b.entry().permissionType() == AclPermissionType.ALLOW && "*".equals(b.entry().host()))
                .collect(Collectors.toSet());
        Set<AclBinding> wanted = new LinkedHashSet<>(desired);
        List<AclBinding> missing = desired.stream().filter(b -> !existing.contains(b)).collect(Collectors.toList());
        List<AclBinding> stale = existing.stream().filter(b -> !wanted.contains(b))
                .sorted(Comparator.comparing(AclBinding::toString)).collect(Collectors.toList());
        if (!missing.isEmpty()) {
            admin.createAcls(missing).all().get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            missing.forEach(b -> log("thêm " + describe(b)));
        }
        if (!stale.isEmpty()) {
            admin.deleteAcls(stale.stream().map(AclBinding::toFilter).collect(Collectors.toList()))
                    .all().get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stale.forEach(b -> log("xoá " + describe(b)));
        }
        log("acl: " + desired.size() + " mong muốn, thêm " + missing.size() + ", xoá " + stale.size());
    }

    /** {@code DescribeConfigs} → {@link AclOperation#DESCRIBE_CONFIGS}. */
    private static AclOperation operation(String name) {
        String constant = name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        try {
            AclOperation operation = AclOperation.valueOf(constant);
            if (operation != AclOperation.UNKNOWN && operation != AclOperation.ANY) {
                return operation;
            }
        } catch (IllegalArgumentException ignored) {
            // báo lỗi bên dưới
        }
        fail("thao tác ACL lạ: " + name);
        return null;
    }

    private static String describe(AclBinding b) {
        return b.entry().principal() + " " + b.entry().operation() + " "
                + b.pattern().resourceType() + "(" + b.pattern().patternType() + "):" + b.pattern().name();
    }

    /** Dòng không trống sau khi bỏ comment, tách theo khoảng trắng. */
    private static List<String[]> rows(Path file) throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            int hash = line.indexOf('#');
            String content = (hash >= 0 ? line.substring(0, hash) : line).trim();
            if (!content.isEmpty()) {
                rows.add(content.split("\\s+"));
            }
        }
        return rows;
    }

    private static String jaas(String user, String password) {
        return "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"" + user
                + "\" password=\"" + password + "\";";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            fail("thiếu biến " + name);
        }
        return value;
    }

    private static void log(String message) {
        System.out.println("[kafka-init] " + message);
    }

    private static void fail(String message) {
        System.err.println("[kafka-init] " + message);
        System.exit(1);
    }
}
