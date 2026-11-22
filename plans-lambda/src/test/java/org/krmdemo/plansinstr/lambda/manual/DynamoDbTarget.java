package org.krmdemo.plansinstr.lambda.manual;

import org.krmdemo.plansinstr.core.model.PlanInstructions;
import org.krmdemo.plansinstr.core.model.enums.PlanStatus;
import org.krmdemo.plansinstr.core.model.enums.PlanType;
import org.krmdemo.plansinstr.core.persistence.UuidV7Generator;
import software.amazon.awssdk.enhanced.dynamodb.document.EnhancedDocument;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.dynamodb.services.local.embedded.DynamoDBEmbedded;
import software.amazon.dynamodb.services.local.shared.access.AmazonDynamoDBLocal;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DynamoDB, with the table and indexes of {@code infra/manual-tests.yaml}:
 * <ul>
 *   <li>key {@code id} (the UUIDv7 as text, so it sorts by creation time);</li>
 *   <li>index {@code byUser}: {@code userId} + {@code id};</li>
 *   <li>index {@code byDda}: {@code ddaAccNum} + {@code id}.</li>
 * </ul>
 * The plan is stored as a native DynamoDB map ({@code payload}), not as a string, so it is
 * JSON that the database understands, like JSONB. The attributes that the indexes and
 * conditions need are copied next to it.
 */
final class DynamoDbTarget implements BenchTarget {

    private final String name;
    private final String about;
    private final DynamoDbClient ddb;
    private final AmazonDynamoDBLocal local;
    private final String table;
    private final JsonMapper json = JsonMapper.builder().build();
    private final UuidV7Generator ids = new UuidV7Generator();

    private DynamoDbTarget(String name, String about, DynamoDbClient ddb, AmazonDynamoDBLocal local, String table) {
        this.name = name;
        this.about = about;
        this.ddb = ddb;
        this.local = local;
        this.table = table;
    }

    /** The real DynamoDB: credentials and region from the environment, the table from infra/manual-tests.yaml. */
    static DynamoDbTarget aws(String table) {
        DynamoDbClient ddb = DynamoDbClient.create();
        return new DynamoDbTarget("dynamodb", "table " + table + " in " + ddb.serviceClientConfiguration().region()
                + ", on demand", ddb, null, table);
    }

    /**
     * DynamoDB Local in this JVM, in memory (SQLite inside), no Docker and no network: what Zonky
     * is for Postgres. The table and its indexes are created here as in infra/manual-tests.yaml.
     */
    static DynamoDbTarget embedded() {
        AmazonDynamoDBLocal local = DynamoDBEmbedded.create();
        DynamoDbClient ddb = local.dynamoDbClient();
        String table = "plans-manual-tests";
        ddb.createTable(b -> b.tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attr("id"), attr("userId"), attr("ddaAccNum"))
                .keySchema(key("id", KeyType.HASH))
                .globalSecondaryIndexes(index("byUser", "userId"), index("byDda", "ddaAccNum")));
        return new DynamoDbTarget("embedded-dynamodb", "DynamoDB Local (in-process, in memory) on the runner",
                ddb, local, table);
    }

    private static AttributeDefinition attr(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }

    private static GlobalSecondaryIndex index(String name, String hashKey) {
        return GlobalSecondaryIndex.builder().indexName(name)
                .keySchema(key(hashKey, KeyType.HASH), key("id", KeyType.RANGE))
                .projection(p -> p.projectionType(ProjectionType.ALL))
                .build();
    }

    @Override public String name() { return name; }
    @Override public Kind kind() { return local != null ? Kind.LOCAL : Kind.CLOUD; }

    @Override public String about() { return about; }

    @Override
    public PlanInstructions create(PlanInstructions plan) {
        if (plan.planId() != null || plan.version() != null) {
            throw new IllegalArgumentException("planId and version must be null when creating a plan");
        }
        Instant now = Instant.now();
        UUID id = ids.next(now.toEpochMilli());
        Map<String, AttributeValue> item = item(plan, id, now, now, 1);
        ddb.putItem(b -> b.tableName(table).item(item).conditionExpression("attribute_not_exists(id)"));
        return toPlan(item);
    }

    @Override
    public Optional<PlanInstructions> load(UUID planId) {
        var r = ddb.getItem(b -> b.tableName(table).key(key(planId)));
        return r.hasItem() && !r.item().isEmpty() ? Optional.of(toPlan(r.item())) : Optional.empty();
    }

    @Override
    public PlanInstructions update(PlanInstructions plan) {
        int expected = Math.toIntExact(plan.version());
        Instant created = plan.createdAt().toInstant(ZoneOffset.UTC);
        Map<String, AttributeValue> item = item(plan, plan.planId(), created, Instant.now(), expected + 1);
        try {
            ddb.putItem(b -> b.tableName(table).item(item)
                    .conditionExpression("version = :expected")
                    .expressionAttributeValues(Map.of(":expected", n(expected))));
        } catch (ConditionalCheckFailedException e) {
            throw new IllegalStateException("version " + expected + " of " + plan.planId() + " is not the stored one", e);
        }
        return toPlan(item);
    }

    @Override
    public boolean delete(UUID planId) {
        var r = ddb.deleteItem(b -> b.tableName(table).key(key(planId)).returnValues(ReturnValue.ALL_OLD));
        return r.hasAttributes() && !r.attributes().isEmpty();
    }

    /** A user has a handful of plans: one query on byUser, then pick in memory. */
    @Override
    public Optional<PlanInstructions> lookup(String userId, PlanType planType) {
        return byUser(userId, null).stream()
                .filter(p -> p.planType() == planType && p.planStatus() != PlanStatus.CANCELLED)
                .max(Comparator.comparing(PlanInstructions::updatedAt));
    }

    @Override
    public List<PlanInstructions> byUser(String userId, PlanStatus status) {
        Map<String, AttributeValue> values = new HashMap<>(Map.of(":u", s(userId)));
        String filter = null;
        if (status != null) {
            filter = "planStatus = :s";
            values.put(":s", s(status.name()));
        }
        String f = filter;
        return ddb.query(b -> b.tableName(table).indexName("byUser")
                        .keyConditionExpression("userId = :u")
                        .filterExpression(f)
                        .expressionAttributeValues(values)
                        .scanIndexForward(false)
                        .limit(50))
                .items().stream().map(this::toPlan).toList();
    }

    @Override
    public List<PlanInstructions> byDdaAccount(String ddaAccNum) {
        return ddb.query(b -> b.tableName(table).indexName("byDda")
                        .keyConditionExpression("ddaAccNum = :d")
                        .expressionAttributeValues(Map.of(":d", s(ddaAccNum)))
                        .scanIndexForward(false)
                        .limit(50))
                .items().stream().map(this::toPlan).toList();
    }

    @Override
    public void close() {
        ddb.close();
        if (local != null) {
            local.shutdown();
        }
    }

    // ---------------------------------------------------------------- mapping

    private Map<String, AttributeValue> item(PlanInstructions p, UUID id, Instant created, Instant updated, int version) {
        PlanInstructions payload = new PlanInstructions(null, p.planType(), p.planStatus(), p.userId(),
                p.ddaAccInfo(), p.invAccInfo(), p.currency(), p.thresholdInfo(), p.restInfo(), null, null, null);
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", s(id.toString()));
        item.put("version", n(version));
        item.put("createdAt", s(created.toString()));
        item.put("lastUpdatedAt", s(updated.toString()));
        item.put("userId", s(p.userId()));
        item.put("planType", s(p.planType().name()));
        item.put("planStatus", s(p.planStatus().name()));
        item.put("ddaAccNum", s(p.ddaAccInfo().ddaAccNum()));
        item.put("payload", AttributeValue.fromM(EnhancedDocument.fromJson(json.writeValueAsString(payload)).toMap()));
        return item;
    }

    private PlanInstructions toPlan(Map<String, AttributeValue> item) {
        String payloadJson = EnhancedDocument.fromAttributeValueMap(item.get("payload").m()).toJson();
        PlanInstructions p = json.readValue(payloadJson, PlanInstructions.class);
        return new PlanInstructions(UUID.fromString(item.get("id").s()),
                p.planType(), p.planStatus(), p.userId(), p.ddaAccInfo(), p.invAccInfo(),
                p.currency(), p.thresholdInfo(), p.restInfo(),
                LocalDateTime.ofInstant(Instant.parse(item.get("createdAt").s()), ZoneOffset.UTC),
                LocalDateTime.ofInstant(Instant.parse(item.get("lastUpdatedAt").s()), ZoneOffset.UTC),
                Long.parseLong(item.get("version").n()));
    }

    private static Map<String, AttributeValue> key(UUID id) {
        return Map.of("id", s(id.toString()));
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    private static AttributeValue n(long v) {
        return AttributeValue.fromN(Long.toString(v));
    }
}
