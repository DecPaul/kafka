/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  *    http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package kafka.server

import kafka.network.SocketServer
import kafka.utils._
import org.apache.kafka.common.protocol.{ApiKeys, Errors}
import org.apache.kafka.common.requests.{DeleteTopicsRequest, DeleteTopicsResponse, MetadataRequest, MetadataResponse}
import org.junit.Assert._
import org.junit.Test

import scala.collection.JavaConverters._

class DeleteTopicsRequestTest extends BaseRequestTest {

  @Test
  def testValidDeleteTopicRequests() {
    val timeout = 10000
    // Single topic
    TestUtils.createTopic(zkUtils, "topic-1", 1, 1, servers)
    validateValidDeleteTopicRequests(new DeleteTopicsRequest.Builder(Set("topic-1").asJava, timeout).build())
    // Multi topic
    TestUtils.createTopic(zkUtils, "topic-3", 5, 2, servers)
    TestUtils.createTopic(zkUtils, "topic-4", 1, 2, servers)
    validateValidDeleteTopicRequests(new DeleteTopicsRequest.Builder(Set("topic-3", "topic-4").asJava, timeout).build())
  }

  private def validateValidDeleteTopicRequests(request: DeleteTopicsRequest): Unit = {
    val response = sendDeleteTopicsRequest(request)

    val error = response.errors.values.asScala.find(_ != Errors.NONE)
    assertTrue(s"There should be no errors, found ${response.errors.asScala}", error.isEmpty)

    request.topics.asScala.foreach { topic =>
      validateTopicIsDeleted(topic)
    }
  }

  @Test
  def testErrorDeleteTopicRequests() {
    val timeout = 30000
    val timeoutTopic = "invalid-timeout"

    // Basic
    validateErrorDeleteTopicRequests(new DeleteTopicsRequest.Builder(Set("invalid-topic").asJava, timeout).build(),
      Map("invalid-topic" -> Errors.UNKNOWN_TOPIC_OR_PARTITION))

    // Partial
    TestUtils.createTopic(zkUtils, "partial-topic-1", 1, 1, servers)
    validateErrorDeleteTopicRequests(new DeleteTopicsRequest.Builder(Set(
      "partial-topic-1",
      "partial-invalid-topic").asJava, timeout).build(),
      Map(
        "partial-topic-1" -> Errors.NONE,
        "partial-invalid-topic" -> Errors.UNKNOWN_TOPIC_OR_PARTITION
      )
    )

    // Timeout
    TestUtils.createTopic(zkUtils, timeoutTopic, 5, 2, servers)
    // Must be a 0ms timeout to avoid transient test failures. Even a timeout of 1ms has succeeded in the past.
    validateErrorDeleteTopicRequests(new DeleteTopicsRequest.Builder(Set(timeoutTopic).asJava, 0).build(),
      Map(timeoutTopic -> Errors.REQUEST_TIMED_OUT))
    // The topic should still get deleted eventually
    TestUtils.waitUntilTrue(() => !servers.head.metadataCache.contains(timeoutTopic), s"Topic $timeoutTopic is never deleted")
    validateTopicIsDeleted(timeoutTopic)
  }

  private def validateErrorDeleteTopicRequests(request: DeleteTopicsRequest, expectedResponse: Map[String, Errors]): Unit = {
    val response = sendDeleteTopicsRequest(request)
    val errors = response.errors.asScala
    assertEquals("The response size should match", expectedResponse.size, response.errors.size)

    expectedResponse.foreach { case (topic, expectedError) =>
      assertEquals("The response error should match", expectedResponse(topic), errors(topic))
      // If no error validate the topic was deleted
      if (expectedError == Errors.NONE) {
        validateTopicIsDeleted(topic)
      }
    }
  }

  @Test
  def testNotController() {
    val request = new DeleteTopicsRequest.Builder(Set("not-controller").asJava, 1000).build()
    val response = sendDeleteTopicsRequest(request, notControllerSocketServer)

    val error = response.errors.asScala.head._2
    assertEquals("Expected controller error when routed incorrectly",  Errors.NOT_CONTROLLER, error)
  }

  private def validateTopicIsDeleted(topic: String): Unit = {
    val metadata = sendMetadataRequest(new MetadataRequest.
        Builder(List(topic).asJava).build).topicMetadata.asScala
    TestUtils.waitUntilTrue (() => !metadata.exists(p => p.topic.equals(topic) && p.error == Errors.NONE),
      s"The topic $topic should not exist")
  }

  private def sendDeleteTopicsRequest(request: DeleteTopicsRequest, socketServer: SocketServer = controllerSocketServer): DeleteTopicsResponse = {
    val response = connectAndSend(request, ApiKeys.DELETE_TOPICS, socketServer)
    DeleteTopicsResponse.parse(response, request.version)
  }

  private def sendMetadataRequest(request: MetadataRequest): MetadataResponse = {
    val response = connectAndSend(request, ApiKeys.METADATA)
    MetadataResponse.parse(response, request.version)
  }
}
