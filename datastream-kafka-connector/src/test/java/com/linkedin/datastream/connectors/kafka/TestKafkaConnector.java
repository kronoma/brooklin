package com.linkedin.datastream.connectors.kafka;

import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import org.I0Itec.zkclient.ZkConnection;
import org.testng.Assert;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import com.codahale.metrics.MetricRegistry;
import kafka.utils.ZkUtils;

import com.linkedin.data.template.StringMap;
import com.linkedin.datastream.common.Datastream;
import com.linkedin.datastream.common.DatastreamDestination;
import com.linkedin.datastream.common.DatastreamMetadataConstants;
import com.linkedin.datastream.common.DatastreamSource;
import com.linkedin.datastream.common.JsonUtils;
import com.linkedin.datastream.common.zk.ZkClient;
import com.linkedin.datastream.kafka.EmbeddedZookeeperKafkaCluster;
import com.linkedin.datastream.metrics.DynamicMetricsManager;
import com.linkedin.datastream.server.api.connector.DatastreamValidationException;


@Test
public class TestKafkaConnector {

  private EmbeddedZookeeperKafkaCluster _kafkaCluster;
  private String _broker;
  private ZkUtils _zkUtils;

  @BeforeTest
  public void setup() throws Exception {
    DynamicMetricsManager.createInstance(new MetricRegistry());
    Properties kafkaConfig = new Properties();
    // we will disable auto topic creation for this test file
    kafkaConfig.setProperty("auto.create.topics.enable", Boolean.FALSE.toString());
    _kafkaCluster = new EmbeddedZookeeperKafkaCluster(kafkaConfig);
    _kafkaCluster.startup();
    _broker = _kafkaCluster.getBrokers().split("\\s*,\\s*")[0];
    _zkUtils =
        new ZkUtils(new ZkClient(_kafkaCluster.getZkConnection()), new ZkConnection(_kafkaCluster.getZkConnection()),
            false);
  }

  @AfterTest
  public void teardown() throws Exception {
    _zkUtils.close();
    _kafkaCluster.shutdown();
  }

  private Properties getDefaultConfig(Properties override) {
    Properties config = new Properties();
    config.put(KafkaConnector.CONFIG_DEFAULT_KEY_SERDE, "keySerde");
    config.put(KafkaConnector.CONFIG_DEFAULT_VALUE_SERDE, "valueSerde");
    config.put(KafkaConnector.CONFIG_COMMIT_INTERVAL_MILLIS, "10000");
    config.put(KafkaConnector.CONFIG_CONSUMER_FACTORY_CLASS, KafkaConsumerFactoryImpl.class.getName());
    if (override != null) {
      config.putAll(override);
    }
    return config;
  }

  private Datastream createDatastream(String name, String topicName) {
    DatastreamSource source = new DatastreamSource();
    source.setConnectionString("kafka://" + _broker + "/" + topicName);
    DatastreamDestination destination = new DatastreamDestination();
    destination.setConnectionString("whatever://bob");
    Datastream datastream = new Datastream();
    datastream.setName(name);
    datastream.setConnectorName("Kafka");
    datastream.setSource(source);
    datastream.setDestination(destination);
    datastream.setMetadata(new StringMap());
    return datastream;
  }

  @Test
  public void testConnectorWithStartPosition() throws UnsupportedEncodingException, DatastreamValidationException {
    String topicName = "testConectorWithStartPosition";
    TestKafkaConnectorTask.produceEvents(_kafkaCluster, _zkUtils, topicName, 0, 100);
    long ts = System.currentTimeMillis();
    TestKafkaConnectorTask.produceEvents(_kafkaCluster, _zkUtils, topicName, 100, 100);
    Datastream ds = createDatastream("testConnectorPopulatesPartitions", topicName);
    Map<Integer, Long> offsets = Collections.singletonMap(0, 100L);
    KafkaConnector connector =
        new KafkaConnector("test", getDefaultConfig(null));
    ds.getMetadata().put(DatastreamMetadataConstants.START_POSITION, JsonUtils.toJson(offsets));
    connector.initializeDatastream(ds, Collections.emptyList());
  }

  @Test(expectedExceptions = DatastreamValidationException.class)
  public void testInitializeDatastreamWithNonexistTopic() throws UnsupportedEncodingException, DatastreamValidationException {
    String topicName = "testInitializeDatastreamWithNonexistTopic";
    Datastream ds = createDatastream("testInitializeDatastreamWithNonexistTopic", topicName);
    KafkaConnector connector =
        new KafkaConnector("test", getDefaultConfig(null));
    connector.initializeDatastream(ds, Collections.emptyList());
  }

  @Test
  public void testPopulatingDefaultSerde() throws Exception {
    String topicName = "testPopulatingDefaultSerde";
    TestKafkaConnectorTask.produceEvents(_kafkaCluster, _zkUtils, topicName, 0, 100);
    TestKafkaConnectorTask.produceEvents(_kafkaCluster, _zkUtils, topicName, 100, 100);
    Datastream ds = createDatastream("testPopulatingDefaultSerde", topicName);
    KafkaConnector connector =
        new KafkaConnector("test", getDefaultConfig(null));
    connector.initializeDatastream(ds, Collections.emptyList());
    Assert.assertTrue(ds.getDestination().hasKeySerDe());
    Assert.assertEquals(ds.getDestination().getKeySerDe(), "keySerde");
    Assert.assertTrue(ds.getDestination().hasPayloadSerDe());
    Assert.assertEquals(ds.getDestination().getPayloadSerDe(), "valueSerde");
  }

  @Test
  public void testConnectorPopulatesPartitions() throws UnsupportedEncodingException, DatastreamValidationException {
    String topicName = "testConnectorPopulatesPartitions";
    TestKafkaConnectorTask.produceEvents(_kafkaCluster, _zkUtils, topicName, 0, 10);

    Datastream ds = createDatastream("testConnectorPopulatesPartitions", topicName);
    KafkaConnector connector =
        new KafkaConnector("test", getDefaultConfig(null));
    connector.initializeDatastream(ds, Collections.emptyList());
    Assert.assertEquals(ds.getSource().getPartitions().intValue(), 1);
  }

  @Test(expectedExceptions = DatastreamValidationException.class)
  public void testConnectorValidatesWhitelistedBroker() throws DatastreamValidationException {
    String topicName = "testConnectorValidatesWhitelistedBroker";

    Datastream ds = createDatastream("testConnectorPopulatesPartitions", topicName);
    Properties override = new Properties();
    override.put(KafkaConnector.CONFIG_WHITE_LISTED_CLUSTERS, "randomBroker:2546");
    KafkaConnector connector = new KafkaConnector("test", getDefaultConfig(override));
    connector.initializeDatastream(ds, Collections.emptyList());
  }
}
