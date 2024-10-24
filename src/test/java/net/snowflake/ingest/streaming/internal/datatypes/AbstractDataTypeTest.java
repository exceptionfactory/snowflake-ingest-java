/*
 * Copyright (c) 2024 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.ingest.streaming.internal.datatypes;

import static net.snowflake.ingest.utils.Constants.ROLE;
import static net.snowflake.ingest.utils.ParameterProvider.BDEC_PARQUET_COMPRESSION_ALGORITHM;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import net.snowflake.ingest.TestUtils;
import net.snowflake.ingest.streaming.OpenChannelRequest;
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestChannel;
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestClient;
import net.snowflake.ingest.streaming.internal.SnowflakeStreamingIngestClientInternal;
import net.snowflake.ingest.utils.Constants;
import net.snowflake.ingest.utils.ParameterProvider;
import net.snowflake.ingest.utils.SFException;
import net.snowflake.ingest.utils.SnowflakeURL;
import net.snowflake.ingest.utils.Utils;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Assert;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public abstract class AbstractDataTypeTest {
  private static final String SOURCE_COLUMN_NAME = "source";
  private static final String VALUE_COLUMN_NAME = "value";

  private static final String SOURCE_STREAMING_INGEST = "STREAMING_INGEST";
  private static final String SOURCE_JDBC = "JDBC";
  private static final String SCHEMA_NAME = "PUBLIC";

  protected static BigInteger MAX_ALLOWED_BIG_INTEGER =
      new BigInteger("99999999999999999999999999999999999999");
  protected static BigInteger MIN_ALLOWED_BIG_INTEGER =
      new BigInteger("-99999999999999999999999999999999999999");

  protected static BigDecimal MAX_ALLOWED_BIG_DECIMAL = new BigDecimal(MAX_ALLOWED_BIG_INTEGER);
  protected static BigDecimal MIN_ALLOWED_BIG_DECIMAL = new BigDecimal(MIN_ALLOWED_BIG_INTEGER);

  protected Connection conn;
  private String databaseName;

  /**
   * Default timezone for newly created channels. If empty, the test will not explicitly set default
   * timezone in the channel builder.
   */
  private Optional<ZoneId> defaultTimezone = Optional.empty();

  private String schemaName = "PUBLIC";
  private SnowflakeStreamingIngestClient client;
  protected static final ObjectMapper objectMapper = new ObjectMapper();

  @Parameters(name = "{index}: {0}")
  public static Object[] parameters() {
    return new Object[] {"GZIP", "ZSTD"};
  }

  @Parameter public String compressionAlgorithm;

  public void before() throws Exception {
    setUp(
        false /* isIceberg */,
        compressionAlgorithm,
        Constants.IcebergSerializationPolicy.COMPATIBLE);
  }

  public void beforeIceberg(
      String compressionAlgorithm, Constants.IcebergSerializationPolicy serializationPolicy)
      throws Exception {
    setUp(true /* isIceberg */, compressionAlgorithm, serializationPolicy);
  }

  protected void setUp(
      boolean isIceberg,
      String compressionAlgorithm,
      Constants.IcebergSerializationPolicy serializationPolicy)
      throws Exception {
    databaseName = String.format("SDK_DATATYPE_COMPATIBILITY_IT_%s", getRandomIdentifier());
    conn = TestUtils.getConnection(true);
    conn.createStatement().execute(String.format("create or replace database %s;", databaseName));
    conn.createStatement().execute(String.format("use database %s;", databaseName));
    conn.createStatement().execute(String.format("use schema %s;", schemaName));

    if (isIceberg) {
      switch (serializationPolicy) {
        case COMPATIBLE:
          conn.createStatement()
              .execute(
                  String.format(
                      "alter schema %s set STORAGE_SERIALIZATION_POLICY = 'COMPATIBLE';",
                      schemaName));
          break;
        case OPTIMIZED:
          conn.createStatement()
              .execute(
                  String.format(
                      "alter schema %s set STORAGE_SERIALIZATION_POLICY = 'OPTIMIZED';",
                      schemaName));
          break;
      }
    }

    conn.createStatement().execute(String.format("use warehouse %s;", TestUtils.getWarehouse()));

    Properties props = TestUtils.getProperties(Constants.BdecVersion.THREE, false);
    if (props.getProperty(ROLE).equals("DEFAULT_ROLE")) {
      props.setProperty(ROLE, "ACCOUNTADMIN");
    }
    props.setProperty(BDEC_PARQUET_COMPRESSION_ALGORITHM, compressionAlgorithm);

    // Override Iceberg mode client lag to 1 second for faster test execution
    Map<String, Object> parameterMap = new HashMap<>();
    parameterMap.put(ParameterProvider.MAX_CLIENT_LAG, 1000L);

    Properties prop = Utils.createProperties(props);
    SnowflakeURL accountURL = new SnowflakeURL(prop.getProperty(Constants.ACCOUNT_URL));
    client =
        new SnowflakeStreamingIngestClientInternal<>(
            "client1", accountURL, prop, parameterMap, isIceberg, false);
  }

  @After
  public void after() throws Exception {
    this.defaultTimezone = Optional.empty();
    if (conn != null) {
      conn.createStatement().executeQuery(String.format("drop database %s", databaseName));
    }
    if (client != null) {
      client.close();
    }
    if (conn != null) {
      conn.close();
    }
  }

  void setChannelDefaultTimezone(ZoneId defaultTimezone) {
    this.defaultTimezone = Optional.of(defaultTimezone);
  }

  protected String createTable(String dataType) throws SQLException {
    String tableName = getRandomIdentifier();
    conn.createStatement()
        .execute(
            String.format(
                "create or replace table %s (%s string, %s %s)",
                tableName, SOURCE_COLUMN_NAME, VALUE_COLUMN_NAME, dataType));

    return tableName;
  }

  protected String createIcebergTable(String dataType) throws SQLException {
    String tableName = getRandomIdentifier();
    String baseLocation =
        String.format("SDK_IT/%s/%s/%s", databaseName, dataType.replace(" ", "_"), tableName);
    conn.createStatement()
        .execute(
            String.format(
                "create or replace iceberg table %s (%s string, %s %s) "
                    + "catalog = 'SNOWFLAKE' "
                    + "external_volume = 'streaming_ingest' "
                    + "base_location = '%s';",
                tableName, SOURCE_COLUMN_NAME, VALUE_COLUMN_NAME, dataType, baseLocation));

    return tableName;
  }

  protected String getRandomIdentifier() {
    return String.format("test_%s", UUID.randomUUID()).replace('-', '_');
  }

  protected SnowflakeStreamingIngestChannel openChannel(String tableName) {
    return openChannel(tableName, OpenChannelRequest.OnErrorOption.ABORT);
  }

  protected SnowflakeStreamingIngestChannel openChannel(
      String tableName, OpenChannelRequest.OnErrorOption onErrorOption) {
    OpenChannelRequest.OpenChannelRequestBuilder requestBuilder =
        OpenChannelRequest.builder("CHANNEL")
            .setDBName(databaseName)
            .setSchemaName(SCHEMA_NAME)
            .setTableName(tableName)
            .setOnErrorOption(onErrorOption);
    defaultTimezone.ifPresent(requestBuilder::setDefaultTimezone);
    OpenChannelRequest openChannelRequest = requestBuilder.build();
    return client.openChannel(openChannelRequest);
  }

  protected Map<String, Object> createStreamingIngestRow(Object value) {
    Map<String, Object> row = new HashMap<>();
    row.put(SOURCE_COLUMN_NAME, SOURCE_STREAMING_INGEST);
    row.put(VALUE_COLUMN_NAME, value);
    return row;
  }

  private <T> void expectError(String dataType, T value, Predicate<Exception> errorMatcher)
      throws Exception {
    String tableName = createTable(dataType);
    SnowflakeStreamingIngestChannel channel = null;
    try {
      channel = openChannel(tableName);
      channel.insertRow(createStreamingIngestRow(value), "0");
      Assert.fail(
          String.format("Inserting value %s for data type %s should fail", value, dataType));
    } catch (Exception e) {
      if (errorMatcher.test(e)) {
        // all good, expected exception has been thrown
      } else {
        e.printStackTrace();
        Assert.fail(String.format("Unexpected exception thrown: %s", e.getMessage()));
      }
    } finally {
      if (channel != null) {
        channel.close().get();
      }
    }
  }

  protected <T> void expectNumberOutOfRangeError(
      String dataType, T value, int maxPowerOf10Exclusive) throws Exception {
    expectError(
        dataType,
        value,
        x ->
            (x instanceof SFException
                && x.getMessage()
                    .contains(
                        String.format(
                            "Number out of representable exclusive range of (-1e%d..1e%d)",
                            maxPowerOf10Exclusive, maxPowerOf10Exclusive))));
  }

  protected <T> void expectNotSupported(String dataType, T value) throws Exception {
    expectError(
        dataType,
        value,
        x ->
            (x instanceof SFException
                && x.getMessage()
                    .contains("The given row cannot be converted to the internal format")));
  }

  /**
   * Simplified version, which does not insert using JDBC. Useful for testing non-JDBC types like
   * BigInteger, java.time.* types, etc.
   */
  <VALUE> void testIngestion(String dataType, VALUE expectedValue, Provider<VALUE> selectProvider)
      throws Exception {
    ingestAndAssert(
        dataType, expectedValue, null, expectedValue, null, selectProvider, false /* isIceberg */);
  }

  <VALUE> void testIcebergIngestion(
      String dataType, VALUE expectedValue, Provider<VALUE> selectProvider) throws Exception {
    ingestAndAssert(
        dataType, expectedValue, null, expectedValue, null, selectProvider, true /* isIceberg */);
  }

  /**
   * Simplified version, which does not insert using JDBC. Useful for testing non-JDBC types like
   * BigInteger, java.time.* types, etc.
   */
  <STREAMING_INGEST_WRITE, JDBC_READ> void testIngestion(
      String dataType,
      STREAMING_INGEST_WRITE streamingIngestWriteValue,
      JDBC_READ expectedValue,
      Provider<JDBC_READ> selectProvider)
      throws Exception {
    ingestAndAssert(
        dataType,
        streamingIngestWriteValue,
        null,
        expectedValue,
        null,
        selectProvider,
        false /* isIceberg */);
  }

  <STREAMING_INGEST_WRITE, JDBC_READ> void testIcebergIngestion(
      String dataType,
      STREAMING_INGEST_WRITE streamingIngestWriteValue,
      JDBC_READ expectedValue,
      Provider<JDBC_READ> selectProvider)
      throws Exception {
    ingestAndAssert(
        dataType,
        streamingIngestWriteValue,
        null,
        expectedValue,
        null,
        selectProvider,
        true /* isIceberg */);
  }

  /**
   * Simplified version where streaming ingest write type, JDBC write type and JDBC read type are
   * the same type
   */
  <T> void testJdbcTypeCompatibility(String typeName, T value, Provider<T> provider)
      throws Exception {
    ingestAndAssert(typeName, value, value, value, provider, provider, false /* isIceberg */);
  }

  /** Simplified version where write value for streaming ingest and JDBC are the same */
  <WRITE, READ> void testJdbcTypeCompatibility(
      String typeName,
      WRITE writeValue,
      READ expectedValue,
      Provider<WRITE> insertProvider,
      Provider<READ> selectProvider)
      throws Exception {
    ingestAndAssert(
        typeName,
        writeValue,
        writeValue,
        expectedValue,
        insertProvider,
        selectProvider,
        false /* isIceberg */);
  }

  /**
   * Ingests values with streaming ingest and JDBC driver, SELECTs them back with WHERE condition
   * and asserts they exist.
   *
   * @param <STREAMING_INGEST_WRITE> Type ingested by streaming ingest
   * @param <JDBC_WRITE> Type written by JDBC driver
   * @param <JDBC_READ> Type read by JDBC driver
   * @param dataType Snowflake data type
   * @param streamingIngestWriteValue Value ingested by streaming ingest
   * @param jdbcWriteValue Value written by JDBC driver
   * @param expectedValue Expected value received from JDBC driver SELECT
   * @param insertProvider JDBC parameter provider for INSERT
   * @param selectProvider JDBC parameter provider for SELECT ... WHERE
   * @param isIceberg whether the table is an iceberg table
   */
  <STREAMING_INGEST_WRITE, JDBC_WRITE, JDBC_READ> void ingestAndAssert(
      String dataType,
      STREAMING_INGEST_WRITE streamingIngestWriteValue,
      JDBC_WRITE jdbcWriteValue,
      JDBC_READ expectedValue,
      Provider<JDBC_WRITE> insertProvider,
      Provider<JDBC_READ> selectProvider,
      boolean isIceberg)
      throws Exception {
    if (jdbcWriteValue == null ^ insertProvider == null)
      throw new IllegalArgumentException(
          "jdbcWriteValue and provider must be both null or not null");
    boolean insertAlsoWithJdbc = jdbcWriteValue != null;
    String tableName = isIceberg ? createIcebergTable(dataType) : createTable(dataType);
    String offsetToken = UUID.randomUUID().toString();

    // Insert using JDBC
    if (insertAlsoWithJdbc) {
      String query = "insert into %s (select ?, ?)";
      PreparedStatement insertStatement = conn.prepareStatement(String.format(query, tableName));
      insertStatement.setString(1, SOURCE_JDBC);
      insertProvider.provide(insertStatement, 2, jdbcWriteValue);
      insertStatement.execute();
    }

    // Ingest using streaming ingest
    SnowflakeStreamingIngestChannel channel = openChannel(tableName);
    channel.insertRow(createStreamingIngestRow(streamingIngestWriteValue), offsetToken);
    TestUtils.waitForOffset(channel, offsetToken);

    // Select expected value and assert that expected number of results is returned (selecting with
    // WHERE also tests pruning).
    // The following is evaluated as true in Snowflake:
    //     select '2020-01-01 14:00:00 +0000'::timestamp_tz = '2020-01-01 12:00:00
    // -0200'::timestamp_tz;
    // Therefore, to correctly test TIMESTAMP_TZ values, we need to compare them as strings.
    // We can do that for all date/time types, not just TIMESTAMP_TZ.
    String selectQuery;
    if (expectedValue == null) {
      selectQuery =
          String.format("select count(*) from %s where %s is NULL", tableName, VALUE_COLUMN_NAME);
    } else if (dataType.toUpperCase().startsWith("TIMESTAMP")) {
      selectQuery =
          String.format(
              "select count(*) from %s where to_varchar(%s, 'YYYY-MM-DD HH24:MI:SS.FF TZHTZM') ="
                  + " ?;",
              tableName, VALUE_COLUMN_NAME);
    } else if (dataType.toUpperCase().startsWith("TIME")) {
      selectQuery =
          String.format(
              "select count(*) from %s where to_varchar(%s, 'HH24:MI:SS.FF TZHTZM') = ?;",
              tableName, VALUE_COLUMN_NAME);
    } else {
      selectQuery = "select count(*) from %s where %s = ?";
    }
    PreparedStatement selectStatement =
        conn.prepareStatement(String.format(selectQuery, tableName, VALUE_COLUMN_NAME));
    if (expectedValue != null) {
      selectProvider.provide(selectStatement, 1, expectedValue);
    }
    ResultSet resultSet = selectStatement.executeQuery();
    Assert.assertTrue(resultSet.next());
    int count = resultSet.getInt(1);
    Assert.assertEquals(insertAlsoWithJdbc ? 2 : 1, count);
    if (!isIceberg) {
      migrateTable(tableName); // migration should always succeed
    }
  }

  <STREAMING_INGEST_WRITE> void assertVariant(
      String dataType,
      STREAMING_INGEST_WRITE streamingIngestWriteValue,
      String expectedValue,
      String expectedType)
      throws Exception {
    assertVariant(
        dataType,
        streamingIngestWriteValue,
        expectedValue,
        expectedType,
        false /*Not max variant or max array*/);
  }

  <STREAMING_INGEST_WRITE> void assertVariant(
      String dataType,
      STREAMING_INGEST_WRITE streamingIngestWriteValue,
      String expectedValue,
      String expectedType,
      boolean isAtMaxValueForDataType)
      throws Exception {

    String tableName = createTable(dataType);
    String offsetToken = UUID.randomUUID().toString();

    // Ingest using streaming ingest
    SnowflakeStreamingIngestChannel channel = openChannel(tableName);
    channel.insertRow(createStreamingIngestRow(streamingIngestWriteValue), offsetToken);
    TestUtils.waitForOffset(channel, offsetToken);

    String query;
    // if added row value is using max possible value, we will not verify its returned type since
    // JDBC doesnt parse it properly because of Jackson Databind version 2.15
    // we will only verify type of it.
    // Check this: https://github.com/snowflakedb/snowflake-sdks-drivers-issues-teamwork/issues/819
    // TODO:: SNOW-1051731
    if (isAtMaxValueForDataType) {
      query = String.format("select typeof(%s) from %s", VALUE_COLUMN_NAME, tableName);
    } else {
      query =
          String.format(
              "select typeof(%s), %s  from %s", VALUE_COLUMN_NAME, VALUE_COLUMN_NAME, tableName);
    }
    ResultSet resultSet = conn.createStatement().executeQuery(query);
    int counter = 0;
    String value = null;
    String typeof = null;
    while (resultSet.next()) {
      counter++;
      typeof = resultSet.getString(1);
      if (!isAtMaxValueForDataType) value = resultSet.getString(2);
    }

    Assert.assertEquals(1, counter);
    if (!isAtMaxValueForDataType) {
      if (expectedValue == null) {
        Assert.assertNull(value);
      } else {
        Assert.assertEquals(objectMapper.readTree(expectedValue), objectMapper.readTree(value));
      }
    }
    Assert.assertEquals(expectedType, typeof);
    migrateTable(tableName); // migration should always succeed
  }

  void assertVariantLiterally(
      String dataType, String writeValue, String expectedValue, String expectedType)
      throws Exception {

    String tableName = createTable(dataType);
    String offsetToken = UUID.randomUUID().toString();

    // Ingest using streaming ingest
    SnowflakeStreamingIngestChannel channel = openChannel(tableName);
    channel.insertRow(createStreamingIngestRow(writeValue), offsetToken);
    TestUtils.waitForOffset(channel, offsetToken);

    final String query =
        String.format(
            "select %s as v1, typeof(v1) as v1_type, parse_json('%s') as v2, typeof(v2) as v2_type"
                + " from %s",
            VALUE_COLUMN_NAME, writeValue, tableName);
    try (ResultSet resultSet = conn.createStatement().executeQuery(query)) {
      resultSet.next();
      Assert.assertEquals(expectedValue, resultSet.getString("V1"));
      Assert.assertEquals(expectedValue, resultSet.getString("V2"));
      Assert.assertEquals(expectedType, resultSet.getString("V1_TYPE"));
      Assert.assertEquals(expectedType, resultSet.getString("V2_TYPE"));
    }
    ;
  }

  protected void migrateTable(String tableName) throws SQLException {
    conn.createStatement().execute(String.format("alter table %s migrate;", tableName));
  }

  /**
   * Ingest multiple values, wait for the latest offset to be committed, migrate the table and
   * assert no errors have been thrown.
   */
  @SafeVarargs
  protected final <STREAMING_INGEST_WRITE> void ingestManyAndMigrate(
      String datatype, final STREAMING_INGEST_WRITE... values) throws Exception {
    String tableName = createTable(datatype);
    SnowflakeStreamingIngestChannel channel = openChannel(tableName);
    String offsetToken = null;
    for (int i = 0; i < values.length; i++) {
      offsetToken = String.format("offsetToken%d", i);
      channel.insertRow(createStreamingIngestRow(values[i]), offsetToken);
    }

    TestUtils.waitForOffset(channel, offsetToken);
    migrateTable(tableName); // migration should always succeed
  }

  protected void testIcebergIngestAndQuery(
      String dataType,
      Iterable<Object> values,
      String queryTemplate,
      Iterable<Object> expectedValues)
      throws Exception {
    String tableName = createIcebergTable(dataType);
    SnowflakeStreamingIngestChannel channel = openChannel(tableName);
    String offsetToken = null;
    for (Object value : values) {
      offsetToken = UUID.randomUUID().toString();
      channel.insertRow(createStreamingIngestRow(value), offsetToken);
    }
    TestUtils.waitForOffset(channel, offsetToken);

    String verificationQuery =
        queryTemplate.replace("{tableName}", tableName).replace("{columnName}", VALUE_COLUMN_NAME);
    ResultSet resultSet = conn.createStatement().executeQuery(verificationQuery);

    for (Object expectedValue : expectedValues) {
      Assertions.assertThat(resultSet.next()).isTrue();
      Object res = resultSet.getObject(1);
      if (expectedValue instanceof BigDecimal) {
        Assertions.assertThat(res)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .usingRecursiveComparison()
            .isEqualTo(expectedValue);
      } else {
        Assertions.assertThat(res).isEqualTo(expectedValue);
      }
    }
    Assertions.assertThat(resultSet.next()).isFalse();
  }
}
