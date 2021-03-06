package org.sagebionetworks.bridge.dynamodb;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.LocalDate;
import org.sagebionetworks.bridge.config.BridgeConfig;
import org.sagebionetworks.bridge.config.BridgeConfigFactory;
import org.sagebionetworks.bridge.config.Environment;
import org.sagebionetworks.bridge.exceptions.BridgeInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBHashKey;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBIndexHashKey;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBIndexRangeKey;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBRangeKey;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBTable;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableResult;
import com.amazonaws.services.dynamodbv2.model.GlobalSecondaryIndex;
import com.amazonaws.services.dynamodbv2.model.GlobalSecondaryIndexDescription;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ListTablesResult;
import com.amazonaws.services.dynamodbv2.model.LocalSecondaryIndex;
import com.amazonaws.services.dynamodbv2.model.LocalSecondaryIndexDescription;
import com.amazonaws.services.dynamodbv2.model.Projection;
import com.amazonaws.services.dynamodbv2.model.ProjectionType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughputDescription;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.dynamodbv2.model.TableStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;

public class DynamoInitializer {

    private static Logger logger = LoggerFactory.getLogger(DynamoInitializer.class);

    // Default capacities are the DynamoDB free tier
    static final long DEFAULT_READ_CAPACITY = 25;
    static final long DEFAULT_WRITE_CAPACITY = 25;

    private static final BridgeConfig CONFIG = BridgeConfigFactory.getConfig();

    private static final AmazonDynamoDB DYNAMO;
    static {
        String awsKey = CONFIG.getProperty("aws.key");
        String secretKey = CONFIG.getProperty("aws.secret.key");
        DYNAMO = new AmazonDynamoDBClient(new BasicAWSCredentials(awsKey, secretKey));
    }

    /**
     * Creates DynamoDB tables, if they do not exist yet, from the annotated types.
     * in the package "org.sagebionetworks.bridge.dynamodb". Throws an error
     * if the table exists but the schema (hash key, range key, and secondary indices)
     * does not match.
     */
    public static void init(String dynamoPackage) {
        beforeInit();
        List<Class<?>> classes = loadDynamoTableClasses(dynamoPackage);
        List<TableDescription> tables = getAnnotatedTables(classes);
        initTables(tables);
    }

    @SafeVarargs
    public static void init(Class<?>... dynamoTables) {
        List<Class<?>> classes = Arrays.asList(dynamoTables);
        List<TableDescription> tables = getAnnotatedTables(classes);
        initTables(tables);
    }

    /**
     * Actions performed before init(), e.g. for phasing out obsolete schemas.
     */
    static void beforeInit() {
    }

    static void deleteTable(Class<?> table) {
        final String tableName = TableNameOverrideFactory.getTableName(table);
        try {
            DescribeTableResult tableResult = DYNAMO.describeTable(tableName);
            TableDescription tableDscr = tableResult.getTable();
            String status = tableDscr.getTableStatus();
            if (TableStatus.DELETING.toString().equalsIgnoreCase(status)) {
                return;
            } else if (!TableStatus.ACTIVE.toString().equalsIgnoreCase(status)) {
                // Must be active to be deleted
                waitForActive(tableDscr);
            }
            logger.info("Deleting table " + tableName);
            DYNAMO.deleteTable(tableName);
            waitForDelete(tableDscr);
            logger.info("Table " + tableName + " deleted.");
        } catch(ResourceNotFoundException e) {
            logger.warn("Table " + tableName + " does not exist.");
        }
    }

    /**
     * Converts the annotated DynamoDBTable types to a list of TableDescription.
     */
    static List<TableDescription> getAnnotatedTables(final List<Class<?>> classes) {
        final List<TableDescription> tables = new ArrayList<TableDescription>();
        for (final Class<?> clazz : classes) {
            final List<KeySchemaElement> keySchema = new ArrayList<>();
            final List<AttributeDefinition> attributes = new ArrayList<>();
            final List<GlobalSecondaryIndexDescription> globalIndices = new ArrayList<>();
            final List<LocalSecondaryIndexDescription> localIndices = new ArrayList<>();
            Method[] methods = clazz.getMethods();
            KeySchemaElement hashKey = null;
            for (Method method : methods) {
                if (method.isAnnotationPresent(DynamoDBHashKey.class)) {
                    // Hash key
                    DynamoDBHashKey hashKeyAttr = method.getAnnotation(DynamoDBHashKey.class);
                    String attrName = hashKeyAttr.attributeName();
                    if (attrName == null || attrName.isEmpty()) {
                        attrName = getAttributeName(method);
                    }
                    hashKey = new KeySchemaElement(attrName, KeyType.HASH);
                    keySchema.add(0, hashKey);
                    ScalarAttributeType attrType = getAttributeType(method);
                    AttributeDefinition attribute = new AttributeDefinition(attrName, attrType);
                    attributes.add(attribute);
                } else if (method.isAnnotationPresent(DynamoDBRangeKey.class)) {
                    // Range key
                    DynamoDBRangeKey rangeKeyAttr = method.getAnnotation(DynamoDBRangeKey.class);
                    String attrName = rangeKeyAttr.attributeName();
                    if (attrName == null || attrName.isEmpty()) {
                        attrName = getAttributeName(method);
                    }
                    KeySchemaElement rangeKey = new KeySchemaElement(attrName, KeyType.RANGE);
                    keySchema.add(rangeKey);
                    ScalarAttributeType attrType = getAttributeType(method);
                    AttributeDefinition attribute = new AttributeDefinition(attrName, attrType);
                    attributes.add(attribute);
                }
            }
            if (hashKey == null) {
                throw new RuntimeException("Missing hash key for DynamoDBTable " + clazz);
            }
            // TODO: The secondary indices code only supports local indices and hash-only global indices.
            // hash-and-range global indices would require rewriting this code to consider indices across multiple
            // methods.
            // secondary indices (local (range) and global (hash-only))
            for (Method method : methods) {
                boolean isLocalIndexKey = false;
                boolean isGlobalIndexKey = false;
                String attrName = null;
                String indexName = null;
                if (method.isAnnotationPresent(DynamoDBIndexRangeKey.class)) {
                    DynamoDBIndexRangeKey indexKey = method.getAnnotation(DynamoDBIndexRangeKey.class);
                    attrName = indexKey.attributeName();
                    indexName = indexKey.localSecondaryIndexName();
                    isLocalIndexKey = true;
                } else if (method.isAnnotationPresent(DynamoDBIndexHashKey.class)) {
                    DynamoDBIndexHashKey indexKey = method.getAnnotation(DynamoDBIndexHashKey.class);
                    attrName = indexKey.attributeName();
                    indexName = indexKey.globalSecondaryIndexName();
                    isGlobalIndexKey = true;
                }

                if (isLocalIndexKey || isGlobalIndexKey) {
                    if (attrName == null || attrName.isEmpty()) {
                        attrName = getAttributeName(method);
                    }
                    ScalarAttributeType attrType = getAttributeType(method);
                    AttributeDefinition attribute = new AttributeDefinition(attrName, attrType);
                    attributes.add(attribute);

                    if (isLocalIndexKey) {
                        LocalSecondaryIndexDescription localIndex = new LocalSecondaryIndexDescription()
                                .withIndexName(indexName)
                                .withKeySchema(hashKey, new KeySchemaElement(attrName, KeyType.RANGE))
                                .withProjection(new Projection().withProjectionType(ProjectionType.ALL));
                        localIndices.add(localIndex);
                    } else {
                        // isGlobalIndexKey is always true in this branch
                        GlobalSecondaryIndexDescription globalIndex = new GlobalSecondaryIndexDescription()
                                .withIndexName(indexName)
                                .withKeySchema(new KeySchemaElement(attrName, KeyType.HASH))
                                .withProjection(new Projection().withProjectionType(ProjectionType.KEYS_ONLY))
                                .withProvisionedThroughput(new ProvisionedThroughputDescription()
                                        .withReadCapacityUnits(DEFAULT_READ_CAPACITY)
                                        .withWriteCapacityUnits(DEFAULT_WRITE_CAPACITY));
                        globalIndices.add(globalIndex);
                    }
                }
            }
            // Throughput
            long writeCapacity = DEFAULT_WRITE_CAPACITY;
            long readCapacity = DEFAULT_READ_CAPACITY;
            if (clazz.isAnnotationPresent(DynamoThroughput.class)) {
                DynamoThroughput throughput = clazz.getAnnotation(DynamoThroughput.class);
                writeCapacity = throughput.writeCapacity();
                readCapacity = throughput.readCapacity();
            }
            final String tableName = TableNameOverrideFactory.getTableName(clazz);
            // Create the table description
            final TableDescription table = (new TableDescription())
                    .withTableName(tableName)
                    .withKeySchema(keySchema)
                    .withAttributeDefinitions(attributes)
                    .withGlobalSecondaryIndexes(globalIndices)
                    .withLocalSecondaryIndexes(localIndices)
                    .withProvisionedThroughput((new ProvisionedThroughputDescription())
                            .withReadCapacityUnits(readCapacity)
                            .withWriteCapacityUnits(writeCapacity));
            tables.add(table);
        }
        return tables;
    }

    /**
     * Uses reflection to get all the annotated DynamoDBTable.
     */
    static List<Class<?>> loadDynamoTableClasses(final String dynamoPackage) {
        final List<Class<?>> classes = new ArrayList<>();
        final ClassLoader classLoader = DynamoInitializer.class.getClassLoader();
        try {
            final ImmutableSet<ClassInfo> classSet = ClassPath.from(classLoader).getTopLevelClasses(dynamoPackage);
            for (ClassInfo classInfo : classSet) {
                Class<?> clazz = classInfo.load();
                if (clazz.isAnnotationPresent(DynamoDBTable.class)) {
                    classes.add(clazz);
                }
            }
            return classes;
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets attribute name from the method.
     */
    static String getAttributeName(final Method method) {
        String attrName = method.getName();
        if (attrName.startsWith("get")) {
            attrName = attrName.substring("get".length());
            if (attrName.length() > 0) {
                // Make sure the first letter is lower case
                char[] chars = attrName.toCharArray();
                chars[0] = Character.toLowerCase(chars[0]);
                attrName = new String(chars);
            }
        }
        return attrName;
    }

    static ScalarAttributeType getAttributeType(Method method) {
        Class<?> clazz = method.getReturnType();
        if (String.class.equals(clazz)) {
            return ScalarAttributeType.S;
        } else if (JsonNode.class.equals(clazz)) {
            return ScalarAttributeType.S;
        } else if (LocalDate.class.equals(clazz)) {
            return ScalarAttributeType.S;
        } else if (Long.class.equals(clazz)) {
            return ScalarAttributeType.N;
        } else if (long.class.equals(clazz)) {
            return ScalarAttributeType.N;
        } else if (Integer.class.equals(clazz)) {
            return ScalarAttributeType.N;
        } else if (int.class.equals(clazz)) {
            return ScalarAttributeType.N;
        } else if (Boolean.class.equals(clazz)) {
            return ScalarAttributeType.B;
        }
        throw new RuntimeException("Unsupported return type " + clazz + " of method " + method.getName());
    }

    static void initTables(final List<TableDescription> tables) {
        Map<String, TableDescription> existingTables = getExistingTables();
        Environment env = CONFIG.getEnvironment();
        if (Environment.UAT.equals(env) || Environment.PROD.equals(env)) {
            StringBuilder builder = new StringBuilder("[");
            for (Map.Entry<String, TableDescription> entry : existingTables.entrySet()) {
                builder.append("(");
                builder.append(entry.getKey());
                builder.append(", ");
                builder.append(entry.getValue().getTableName());
                builder.append(", ");
                builder.append(entry.getValue().getTableStatus());
                builder.append("), ");
            }
            builder.append("]");
            logger.info("Existing tables: " + builder.toString());
        }
        for (TableDescription table : tables) {
            if (!existingTables.containsKey(table.getTableName())) {
                CreateTableRequest createTableRequest = getCreateTableRequest(table);
                logger.info("Creating table " + table.getTableName());
                DYNAMO.createTable(createTableRequest);
            } else {
                final TableDescription existingTable = existingTables.get(table.getTableName());
                compareSchema(table, existingTable);
            }
            waitForActive(table);
        }
        logger.info("DynamoDB tables are ready.");
    }

    static Map<String, TableDescription> getExistingTables() {
        Map<String, TableDescription> existingTables = new HashMap<>();
        String lastTableName = null;
        ListTablesResult listTablesResult = DYNAMO.listTables();
        do {
            for (String tableName : listTablesResult.getTableNames()) {
                DescribeTableResult describeResult = DYNAMO.describeTable(new DescribeTableRequest(tableName));
                TableDescription table = describeResult.getTable();
                existingTables.put(tableName, table);
            }
            lastTableName = listTablesResult.getLastEvaluatedTableName();
            if (lastTableName != null) {
                listTablesResult = DYNAMO.listTables(lastTableName);
            }
        } while (lastTableName != null);
        return existingTables;
    }

    static CreateTableRequest getCreateTableRequest(TableDescription table) {
        CreateTableRequest request = new CreateTableRequest()
                .withTableName(table.getTableName())
                .withKeySchema(table.getKeySchema())
                .withAttributeDefinitions(table.getAttributeDefinitions());
        // ProvisionedThroughputDescription -> ProvisionedThroughput
        ProvisionedThroughput throughput = new ProvisionedThroughput(
                table.getProvisionedThroughput().getReadCapacityUnits(),
                table.getProvisionedThroughput().getWriteCapacityUnits());
        request.setProvisionedThroughput(throughput);

        // GlobalSecondaryIndexDescription -> GlobalSecondaryIndex
        List<GlobalSecondaryIndex> globalIndices = new ArrayList<>();
        List<GlobalSecondaryIndexDescription> globalIndexDescs = table.getGlobalSecondaryIndexes();
        for (GlobalSecondaryIndexDescription globalIndexDesc : globalIndexDescs) {
            GlobalSecondaryIndex globalIndex = new GlobalSecondaryIndex()
                    .withIndexName(globalIndexDesc.getIndexName())
                    .withKeySchema(globalIndexDesc.getKeySchema())
                    .withProjection(globalIndexDesc.getProjection())
                    .withProvisionedThroughput(new ProvisionedThroughput(
                            globalIndexDesc.getProvisionedThroughput().getReadCapacityUnits(),
                            globalIndexDesc.getProvisionedThroughput().getWriteCapacityUnits()));
            globalIndices.add(globalIndex);
        }
        if (globalIndices.size() > 0) {
            request.setGlobalSecondaryIndexes(globalIndices);
        }

        // LocalSecondaryIndexDescription -> LocalSecondaryIndex
        List<LocalSecondaryIndex> localIndices = new ArrayList<>();
        List<LocalSecondaryIndexDescription> localIndexDescs = table.getLocalSecondaryIndexes();
        for (LocalSecondaryIndexDescription localIndexDesc : localIndexDescs) {
            LocalSecondaryIndex localIndex = new LocalSecondaryIndex()
                    .withIndexName(localIndexDesc.getIndexName())
                    .withKeySchema(localIndexDesc.getKeySchema())
                    .withProjection(localIndexDesc.getProjection());
            localIndices.add(localIndex);
        }
        if (localIndices.size() > 0) {
            request.setLocalSecondaryIndexes(localIndices);
        }

        return request;
    }

    /**
     * Compares hash key, range key of the two tables. Throws an exception
     * if there is difference.
     */
    static void compareSchema(TableDescription table1, TableDescription table2) {
        if (table1.getTableName().equals(table2.getTableName())) {
            compareKeySchema(table1, table2);
        }
    }

    static void compareKeySchema(TableDescription table1, TableDescription table2) {
        List<KeySchemaElement> keySchema1 = table1.getKeySchema();
        List<KeySchemaElement> keySchema2 = table2.getKeySchema();
        compareKeySchema(keySchema1, keySchema2);
    }

    private static void compareKeySchema(List<KeySchemaElement> keySchema1, List<KeySchemaElement> keySchema2) {
        if (keySchema1.size() != keySchema2.size()) {
            throw new BridgeInitializationException("Key schemas have different number of key elements.");
        }
        Map<String, KeySchemaElement> keySchemaMap1 = new HashMap<>();
        for (KeySchemaElement ele1 : keySchema1) {
            keySchemaMap1.put(ele1.getAttributeName(), ele1);
        }
        for (KeySchemaElement ele2 : keySchema2) {
            KeySchemaElement ele1 = keySchemaMap1.get(ele2.getAttributeName());
            if (ele1 == null) {
                throw new BridgeInitializationException("Missing key " + ele2.getAttributeName() + " in schema 1.");
            }
            if (!ele1.equals(ele2)) {
                throw new BridgeInitializationException("Different key schema for key " + ele2.getAttributeName());
            }
        }
    }

    /**
     * Wait for the table to become ACTIVE.
     */
    private static void waitForActive(TableDescription table) {
        DescribeTableResult describeResult = DYNAMO.describeTable(
                new DescribeTableRequest(table.getTableName()));
        table = describeResult.getTable();
        while (!TableStatus.ACTIVE.name().equalsIgnoreCase(table.getTableStatus())) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                throw new RuntimeException("Shouldn't be interrupted.", e);
            }
            describeResult = DYNAMO.describeTable(new DescribeTableRequest(table.getTableName()));
            table = describeResult.getTable();
        }
    }

    /**
     * Wait for the table to be deleted.
     */
    private static void waitForDelete(TableDescription table) {
        DescribeTableResult describeResult = DYNAMO.describeTable(
                new DescribeTableRequest(table.getTableName()));
        table = describeResult.getTable();
        while (true) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                throw new RuntimeException("Shouldn't be interrupted.", e);
            }
            try {
                describeResult = DYNAMO.describeTable(new DescribeTableRequest(table.getTableName()));
                table = describeResult.getTable();
            } catch (ResourceNotFoundException e) {
                return;
            }
        }
    }
}
