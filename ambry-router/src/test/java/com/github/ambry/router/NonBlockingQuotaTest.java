/*
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.router;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.account.Account;
import com.github.ambry.account.AccountService;
import com.github.ambry.account.Container;
import com.github.ambry.accountstats.AccountStatsStore;
import com.github.ambry.commons.LoggingNotificationSystem;
import com.github.ambry.commons.RetainingAsyncWritableChannel;
import com.github.ambry.config.QuotaConfig;
import com.github.ambry.config.RouterConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.messageformat.MessageFormatRecord;
import com.github.ambry.protocol.GetOption;
import com.github.ambry.quota.AmbryQuotaManager;
import com.github.ambry.quota.QuotaAction;
import com.github.ambry.quota.QuotaChargeCallback;
import com.github.ambry.quota.QuotaEnforcer;
import com.github.ambry.quota.QuotaException;
import com.github.ambry.quota.QuotaManager;
import com.github.ambry.quota.QuotaMethod;
import com.github.ambry.quota.QuotaMetrics;
import com.github.ambry.quota.QuotaMode;
import com.github.ambry.quota.QuotaName;
import com.github.ambry.quota.QuotaRecommendationMergePolicy;
import com.github.ambry.quota.QuotaResource;
import com.github.ambry.quota.QuotaResourceType;
import com.github.ambry.quota.QuotaUtils;
import com.github.ambry.quota.SimpleQuotaRecommendationMergePolicy;
import com.github.ambry.quota.capacityunit.AmbryCUQuotaEnforcer;
import com.github.ambry.quota.capacityunit.CapacityUnit;
import com.github.ambry.rest.MockRestRequest;
import com.github.ambry.rest.RequestPath;
import com.github.ambry.rest.RestMethod;
import com.github.ambry.rest.RestRequest;
import com.github.ambry.rest.RestServiceException;
import com.github.ambry.rest.RestUtils;
import com.github.ambry.utils.Utils;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.ambry.utils.TestUtils.*;
import static org.junit.Assert.*;


/**
 * Class to test the {@link NonBlockingRouter} with {@link com.github.ambry.quota.PostProcessQuotaChargeCallback}.
 */
@RunWith(Parameterized.class)
public class NonBlockingQuotaTest extends NonBlockingRouterTestBase {
  private static final Logger logger = LoggerFactory.getLogger(NonBlockingQuotaTest.class);
  private final QuotaMode quotaMode;
  private final long quotaAccountingSize = 1024L;

  /**
   * Initialize parameters common to all tests.
   * @param testEncryption {@code true} to test with encryption enabled. {@code false} otherwise.
   * @param metadataContentVersion the metadata content version to test with.
   * @param quotaMode {@link QuotaMode} object.
   * @throws Exception if initialization fails.
   */
  public NonBlockingQuotaTest(boolean testEncryption, int metadataContentVersion, QuotaMode quotaMode)
      throws Exception {
    super(testEncryption, metadataContentVersion, false);
    this.quotaMode = quotaMode;
  }

  /**
   * Running for both regular and encrypted blobs, and versions 2 and 3 of MetadataContent
   * @return an array with all four different choices
   */
  @Parameterized.Parameters
  public static List<Object[]> data() {
    return Arrays.asList(new Object[][]{{true, MessageFormatRecord.Metadata_Content_Version_V2, QuotaMode.THROTTLING},
        {true, MessageFormatRecord.Metadata_Content_Version_V3, QuotaMode.THROTTLING},
        {false, MessageFormatRecord.Metadata_Content_Version_V2, QuotaMode.THROTTLING},
        {false, MessageFormatRecord.Metadata_Content_Version_V3, QuotaMode.THROTTLING},
        {true, MessageFormatRecord.Metadata_Content_Version_V2, QuotaMode.TRACKING},
        {true, MessageFormatRecord.Metadata_Content_Version_V3, QuotaMode.TRACKING},
        {false, MessageFormatRecord.Metadata_Content_Version_V2, QuotaMode.TRACKING},
        {false, MessageFormatRecord.Metadata_Content_Version_V3, QuotaMode.TRACKING}});
  }

  /**
   * Build the default quota enforcer and source pair json.
   * @return JSONObject representing the pair json.
   */
  private static String buildQuotaEnforcerSourceInfoPair() {
    JSONObject jsonObject = new JSONObject();
    JSONArray jsonArray = new JSONArray();
    jsonObject.put(QuotaConfig.ENFORCER_STR, "com.github.ambry.quota.capacityunit.AmbryCUQuotaEnforcerFactory");
    jsonObject.put(QuotaConfig.SOURCE_STR, "com.github.ambry.router.TestCUQuotaSourceFactory");
    jsonArray.put(jsonObject);
    jsonObject = new JSONObject();
    jsonObject.put(QuotaConfig.ENFORCER_STR, "com.github.ambry.router.RejectingQuotaEnforcerFactory");
    jsonObject.put(QuotaConfig.SOURCE_STR, "com.github.ambry.router.TestCUQuotaSourceFactory");
    jsonArray.put(jsonObject);
    return new JSONObject().put(QuotaConfig.QUOTA_ENFORCER_SOURCE_PAIR_INFO_STR, jsonArray).toString();
  }

  /**
   * Test Router with single scaling unit for correct accounting in {@link QuotaChargeCallback}.
   */
  @Test
  public void testRouterWithQuotaCallback() throws Exception {
    try {
      setRouter();
      assertExpectedThreadCounts(2, 1);
      AtomicLong listenerCalledCount = new AtomicLong(0);
      int expectedChargeCallbackCount = 0;
      // create a quota charge callback that increments an atomic counter everytime its called.
      // Also tests that in case quota if charged in tracking mode with throttleInProgress config set to false
      // then the requests go through even in case of exception.
      QuotaChargeCallback quotaChargeCallback = new QuotaChargeCallback() {
        @Override
        public QuotaAction checkAndCharge(boolean shouldCheckExceedAllowed, boolean forceCharge, long chunkSize)
            throws QuotaException {
          listenerCalledCount.addAndGet(chunkSize);
          throw new QuotaException("exception during check and charge",
              new RouterException("Quota exceeded.", RouterErrorCode.TooManyRequests), false);
        }

        @Override
        public QuotaAction checkAndCharge(boolean shouldCheckExceedAllowed, boolean forceCharge) throws QuotaException {
          return checkAndCharge(shouldCheckExceedAllowed, forceCharge, quotaAccountingSize);
        }

        @Override
        public QuotaResource getQuotaResource() {
          return null;
        }

        @Override
        public QuotaMethod getQuotaMethod() {
          return null;
        }

        @Override
        public QuotaConfig getQuotaConfig() {
          return new QuotaConfig(new VerifiableProperties(new Properties()));
        }
      };

      // test for a composite blob.
      int blobSize = 3000;
      setOperationParams(blobSize, TTL_SECS);
      String compositeBlobId =
          router.putBlob(putBlobProperties, putUserMetadata, putChannel, PutBlobOptions.DEFAULT, null,
              quotaChargeCallback).get();
      expectedChargeCallbackCount += blobSize;
      assertEquals(expectedChargeCallbackCount, listenerCalledCount.get());
      RetainingAsyncWritableChannel retainingAsyncWritableChannel = new RetainingAsyncWritableChannel();
      router.getBlob(compositeBlobId, new GetBlobOptionsBuilder().build(), null, quotaChargeCallback)
          .get()
          .getBlobDataChannel()
          .readInto(retainingAsyncWritableChannel, null)
          .get();
      expectedChargeCallbackCount += blobSize;
      // read out all the chunks to make sure all the chunks are consumed and accounted for.
      retainingAsyncWritableChannel.consumeContentAsInputStream().close();
      assertEquals(expectedChargeCallbackCount, listenerCalledCount.get());

      // test for regular blobs.
      setOperationParams();
      List<String> blobIds = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        setOperationParams();
        String blobId = router.putBlob(putBlobProperties, putUserMetadata, putChannel, PutBlobOptions.DEFAULT, null,
            quotaChargeCallback).get();
        assertEquals(expectedChargeCallbackCount += PUT_CONTENT_SIZE, listenerCalledCount.get());
        logger.info("Put blob {}", blobId);
        blobIds.add(blobId);
      }
      setOperationParams();

      for (String blobId : blobIds) {
        router.getBlob(blobId, new GetBlobOptionsBuilder().build(), null, quotaChargeCallback).get();
        assertEquals(expectedChargeCallbackCount += PUT_CONTENT_SIZE, listenerCalledCount.get());
        router.updateBlobTtl(blobId, null, Utils.Infinite_Time, null, quotaChargeCallback).get();
        assertEquals(expectedChargeCallbackCount += quotaAccountingSize, listenerCalledCount.get());
        router.getBlob(blobId, new GetBlobOptionsBuilder().build(), null, quotaChargeCallback).get();
        assertEquals(expectedChargeCallbackCount += PUT_CONTENT_SIZE, listenerCalledCount.get());
        router.getBlob(blobId, new GetBlobOptionsBuilder().operationType(GetBlobOptions.OperationType.BlobInfo).build(),
            null, quotaChargeCallback).get();
        assertEquals(expectedChargeCallbackCount += quotaAccountingSize, listenerCalledCount.get());
        router.deleteBlob(blobId, null, null, quotaChargeCallback).get();
        assertEquals(expectedChargeCallbackCount += quotaAccountingSize, listenerCalledCount.get());
        try {
          router.getBlob(blobId, new GetBlobOptionsBuilder().build(), null, quotaChargeCallback).get();
          fail("Get blob should fail");
        } catch (ExecutionException e) {
          RouterException r = (RouterException) e.getCause();
          Assert.assertEquals("BlobDeleted error is expected", RouterErrorCode.BlobDeleted, r.getErrorCode());
          assertEquals(expectedChargeCallbackCount, listenerCalledCount.get());
        }
        router.getBlob(blobId, new GetBlobOptionsBuilder().getOption(GetOption.Include_Deleted_Blobs).build(), null,
            quotaChargeCallback).get();
        assertEquals(expectedChargeCallbackCount += PUT_CONTENT_SIZE, listenerCalledCount.get());
        router.getBlob(blobId, new GetBlobOptionsBuilder().getOption(GetOption.Include_All).build(), null,
            quotaChargeCallback).get();
        assertEquals(expectedChargeCallbackCount += PUT_CONTENT_SIZE, listenerCalledCount.get());
      }

      // test for stitched blobs.
      blobIds = new ArrayList<>();
      int stitchedBlobCount = 2;
      for (int i = 0; i < stitchedBlobCount; i++) {
        setOperationParams();
        String blobId = router.putBlob(putBlobProperties, putUserMetadata, putChannel, PutBlobOptions.DEFAULT, null,
            quotaChargeCallback).get();
        assertEquals(expectedChargeCallbackCount += PUT_CONTENT_SIZE, listenerCalledCount.get());
        logger.info("Put blob {}", blobId);
        blobIds.add(blobId);
      }

      String stitchedBlobId = router.stitchBlob(putBlobProperties, putUserMetadata, blobIds.stream()
          .map(blobId -> new ChunkInfo(blobId, PUT_CONTENT_SIZE, Utils.Infinite_Time))
          .collect(Collectors.toList()), null, quotaChargeCallback).get();
      assertEquals(expectedChargeCallbackCount, listenerCalledCount.get());

      retainingAsyncWritableChannel = new RetainingAsyncWritableChannel();
      router.getBlob(stitchedBlobId, new GetBlobOptionsBuilder().build(), null, quotaChargeCallback)
          .get()
          .getBlobDataChannel()
          .readInto(retainingAsyncWritableChannel, null)
          .get();
      // read out all the chunks to make sure all the chunks are consumed and accounted for.
      retainingAsyncWritableChannel.consumeContentAsInputStream().close();
      assertEquals(expectedChargeCallbackCount += (PUT_CONTENT_SIZE * stitchedBlobCount), listenerCalledCount.get());

      router.updateBlobTtl(stitchedBlobId, null, Utils.Infinite_Time, null, quotaChargeCallback).get();
      assertEquals(expectedChargeCallbackCount += quotaAccountingSize, listenerCalledCount.get());

      router.deleteBlob(stitchedBlobId, null, null, quotaChargeCallback).get();
      assertEquals(expectedChargeCallbackCount + quotaAccountingSize, listenerCalledCount.get());
    } finally {
      router.close();
      assertExpectedThreadCounts(0, 0);

      //submission after closing should return a future that is already done.
      assertClosed();
    }
  }

  /**
   * Test default {@link QuotaChargeCallback} doesn't charge anything and doesn't error out when throttling is disabled.
   */
  @Test
  public void testRouterWithDefaultQuotaCallback() throws Exception {
    try {
      setRouter();
      assertExpectedThreadCounts(2, 1);
      AtomicInteger listenerCalledCount = new AtomicInteger(0);
      QuotaConfig quotaConfig = new QuotaConfig(new VerifiableProperties(new Properties()));
      QuotaManager quotaManager =
          new ChargeTesterQuotaManager(quotaConfig, new SimpleQuotaRecommendationMergePolicy(quotaConfig),
              accountService, null, new QuotaMetrics(new MetricRegistry()), listenerCalledCount);
      QuotaChargeCallback quotaChargeCallback = QuotaUtils.buildQuotaChargeCallback(null, quotaManager, true);

      int blobSize = 3000;
      setOperationParams(blobSize, TTL_SECS);
      String compositeBlobId =
          router.putBlob(putBlobProperties, putUserMetadata, putChannel, PutBlobOptions.DEFAULT, null,
              quotaChargeCallback).get();
      assertEquals(0, listenerCalledCount.get());
      RetainingAsyncWritableChannel retainingAsyncWritableChannel = new RetainingAsyncWritableChannel();
      router.getBlob(compositeBlobId, new GetBlobOptionsBuilder().build(), null, quotaChargeCallback)
          .get()
          .getBlobDataChannel()
          .readInto(retainingAsyncWritableChannel, null)
          .get();
      // read out all the chunks.
      retainingAsyncWritableChannel.consumeContentAsInputStream().close();
      assertEquals(0, listenerCalledCount.get());
    } finally {
      router.close();
      assertExpectedThreadCounts(0, 0);

      //submission after closing should return a future that is already done.
      assertClosed();
    }
  }

  /**
   * Test for {@link com.github.ambry.quota.PreProcessQuotaChargeCallback}.
   */
  @Test
  public void testRouterWithPreProcessQuotaCallback() throws Exception {
    try {
      Properties properties = getNonBlockingRouterProperties("DC1");
      properties.setProperty(QuotaConfig.BANDWIDTH_THROTTLING_FEATURE_ENABLED, "true");
      properties.setProperty(QuotaConfig.REQUEST_QUOTA_ENFORCER_SOURCE_PAIR_INFO_JSON,
          buildQuotaEnforcerSourceInfoPair());
      properties.setProperty(QuotaConfig.THROTTLING_MODE, quotaMode.name());
      properties.setProperty(QuotaConfig.CU_QUOTA_AGGREGATION_WINDOW_IN_SECS, "86400");
      properties.setProperty(RouterConfig.OPERATION_CONTROLLER, QuotaAwareOperationController.class.getCanonicalName());
      setRouter(properties, mockServerLayout, new LoggingNotificationSystem());
      assertExpectedThreadCounts(2, 1);
      AtomicInteger listenerCalledCount = new AtomicInteger(0);

      QuotaConfig quotaConfig = new QuotaConfig(new VerifiableProperties(properties));
      Account account = accountService.createAndAddRandomAccount(QuotaResourceType.ACCOUNT);
      ChargeTesterQuotaManager chargeTesterQuotaManager =
          new ChargeTesterQuotaManager(quotaConfig, new SimpleQuotaRecommendationMergePolicy(quotaConfig),
              accountService, null, new QuotaMetrics(new MetricRegistry()), listenerCalledCount);
      chargeTesterQuotaManager.init();
      TestCUQuotaSource quotaSource = chargeTesterQuotaManager.getTestCuQuotaSource();
      RestRequest restRequest = createRestRequest(RestMethod.POST.name(), null, account, account.getAllContainers().iterator().next());
      QuotaChargeCallback quotaChargeCallback = QuotaUtils.buildQuotaChargeCallback(restRequest, chargeTesterQuotaManager, true);
      int blobSize = 3000;
      setOperationParams(blobSize, TTL_SECS, account.getId(), account.getAllContainers().iterator().next().getId());
      quotaSource.getCuQuota().put(String.valueOf(account.getId()), new CapacityUnit(8, 8));
      quotaSource.getCuUsage().put(String.valueOf(account.getId()), new CapacityUnit());
      String compositeBlobId =
          router.putBlob(putBlobProperties, putUserMetadata, putChannel, PutBlobOptions.DEFAULT, null,
              quotaChargeCallback).get();
        CapacityUnit quotaUsage = quotaSource.getCuUsage().get(String.valueOf(account.getId()));
        Assert.assertEquals(8, quotaUsage.getWcu());
        Assert.assertEquals(0, quotaUsage.getRcu());
        RetainingAsyncWritableChannel retainingAsyncWritableChannel = new RetainingAsyncWritableChannel();
        restRequest = createRestRequest(RestMethod.GET.name(), null, account, account.getAllContainers().iterator().next());
        quotaChargeCallback = QuotaUtils.buildQuotaChargeCallback(restRequest, chargeTesterQuotaManager, true);
        router.getBlob(compositeBlobId, new GetBlobOptionsBuilder().build(), null, quotaChargeCallback)
            .get()
            .getBlobDataChannel()
            .readInto(retainingAsyncWritableChannel, null)
            .get();
        // read out all the chunks.
        retainingAsyncWritableChannel.consumeContentAsInputStream().close();
        quotaUsage = quotaSource.getCuUsage().get(String.valueOf(account.getId()));
        Assert.assertEquals(8, quotaUsage.getWcu());
        Assert.assertEquals(8, quotaUsage.getRcu());
        retainingAsyncWritableChannel = new RetainingAsyncWritableChannel();
        try {
          router.getBlob(compositeBlobId, new GetBlobOptionsBuilder().build(), null, quotaChargeCallback).get();
          if(quotaMode == QuotaMode.THROTTLING) {
            fail("getBlob should throw exception if request is be rejected by an enforcer and quota mode is throttling.");
          }
      } catch (ExecutionException ex) {
        if(quotaMode == QuotaMode.TRACKING) {
          fail("getBlob should throw exception if request is be rejected by an enforcer and quota mode is tracking.");
        } else {
          Assert.assertEquals(RouterErrorCode.TooManyRequests, ((RouterException) ex.getCause()).getErrorCode());
        }
      }
      retainingAsyncWritableChannel.consumeContentAsInputStream().close();
    } finally {
      if (router != null) {
        router.close();
        assertExpectedThreadCounts(0, 0);

        //submission after closing should return a future that is already done.
        assertClosed();
      }
    }
  }

  /**
   * Method to easily create {@link RestRequest} objects containing a specific request, account and container.
   * @param restMethod string representation of the rest method.
   * @param uri string representation of the desired URI.
   * @param account {@link Account} object associated with the request.
   * @param container {@link Container} object associated with the request.
   * @return A {@link RestRequest} object that defines the request required by the input.
   * @throws JSONException
   * @throws UnsupportedEncodingException
   * @throws URISyntaxException
   */
  private RestRequest createRestRequest(String restMethod, String uri, Account account, Container container)
      throws JSONException, UnsupportedEncodingException, URISyntaxException, RestServiceException {
    JSONObject request = new JSONObject();
    request.put(MockRestRequest.REST_METHOD_KEY, (restMethod == null) ? JSONObject.NULL : restMethod);
    request.put(MockRestRequest.URI_KEY, ((uri == null) ? JSONObject.NULL : uri));
    JSONObject headers = new JSONObject();
    headers.putOpt(RestUtils.InternalKeys.TARGET_ACCOUNT_KEY, ((account == null) ? JSONObject.NULL : account));
    headers.putOpt(RestUtils.InternalKeys.TARGET_CONTAINER_KEY, ((container == null) ? JSONObject.NULL : container));
    headers.putOpt(RestUtils.InternalKeys.REQUEST_PATH,
        RequestPath.parse("/", Collections.emptyMap(), Collections.emptyList(), "ambry-test"));
    request.put(MockRestRequest.HEADERS_KEY, headers);
    return new MockRestRequest(request, null);
  }

  /**
   * {@link AmbryQuotaManager} extension to test behavior with default implementation.
   */
  static class ChargeTesterQuotaManager extends AmbryQuotaManager {
    private final AtomicInteger chargeCalledCount;

    /**
     * Constructor for {@link ChargeTesterQuotaManager}.
     * @param quotaConfig {@link QuotaConfig} object.
     * @param quotaRecommendationMergePolicy {@link QuotaRecommendationMergePolicy} object that makes the overall recommendation.
     * @param accountService {@link AccountService} object to get all the accounts and container information.
     * @param accountStatsStore {@link AccountStatsStore} object to get all the account stats related information.
     * @param quotaMetrics {@link QuotaMetrics} object.
     * @throws ReflectiveOperationException in case of any exception.
     */
    public ChargeTesterQuotaManager(QuotaConfig quotaConfig,
        QuotaRecommendationMergePolicy quotaRecommendationMergePolicy, AccountService accountService,
        AccountStatsStore accountStatsStore, QuotaMetrics quotaMetrics, AtomicInteger chargeCalledCount)
        throws ReflectiveOperationException {
      super(quotaConfig, quotaRecommendationMergePolicy, accountService, accountStatsStore, quotaMetrics);
      this.chargeCalledCount = chargeCalledCount;
    }

    @Override
    public QuotaAction chargeAndRecommend(RestRequest restRequest, Map<QuotaName, Double> requestCostMap,
        boolean shouldCheckIfQuotaExceedAllowed, boolean forceCharge) throws QuotaException {
      chargeCalledCount.incrementAndGet();
      return super.chargeAndRecommend(restRequest, requestCostMap, shouldCheckIfQuotaExceedAllowed, forceCharge);
    }

    /**
     * @return TestCUQuotaSource object from the {@link QuotaEnforcer}s.
     */
    public TestCUQuotaSource getTestCuQuotaSource() {
      for (QuotaEnforcer quotaEnforcer : quotaEnforcers) {
        if (quotaEnforcer instanceof AmbryCUQuotaEnforcer && !(quotaEnforcer instanceof RejectingQuotaEnforcer)) {
          return (TestCUQuotaSource) quotaEnforcer.getQuotaSource();
        }
      }
      throw new IllegalStateException("could not find TestCUQuotaSource in QuotaManager.");
    }
  }
}