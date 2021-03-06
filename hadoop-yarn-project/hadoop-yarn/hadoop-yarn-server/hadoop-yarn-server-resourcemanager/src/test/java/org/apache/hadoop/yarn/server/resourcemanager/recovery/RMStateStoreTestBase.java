/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.recovery;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import javax.crypto.SecretKey;

import junit.framework.Assert;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ha.ClientBaseWithFixes;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.delegation.DelegationKey;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.impl.pb.ApplicationSubmissionContextPBImpl;
import org.apache.hadoop.yarn.api.records.impl.pb.ContainerPBImpl;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier;
import org.apache.hadoop.yarn.security.client.RMDelegationTokenIdentifier;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.RMStateStore.ApplicationAttemptState;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.RMStateStore.ApplicationState;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.RMStateStore.RMDTSecretManagerState;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.RMStateStore.RMState;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppState;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptState;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.event.RMAppAttemptNewSavedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.security.AMRMTokenSecretManager;
import org.apache.hadoop.yarn.server.resourcemanager.security.ClientToAMTokenSecretManagerInRM;
import org.apache.hadoop.yarn.util.ConverterUtils;

public class RMStateStoreTestBase extends ClientBaseWithFixes{

  public static final Log LOG = LogFactory.getLog(RMStateStoreTestBase.class);

  static class TestDispatcher implements
      Dispatcher, EventHandler<RMAppAttemptNewSavedEvent> {

    ApplicationAttemptId attemptId;
    Exception storedException;

    boolean notified = false;

    @SuppressWarnings("rawtypes")
    @Override
    public void register(Class<? extends Enum> eventType,
                         EventHandler handler) {
    }

    @Override
    public void handle(RMAppAttemptNewSavedEvent event) {
      assertEquals(attemptId, event.getApplicationAttemptId());
      assertEquals(storedException, event.getStoredException());
      notified = true;
      synchronized (this) {
        notifyAll();
      }
    }

    @SuppressWarnings("rawtypes")
    @Override
    public EventHandler getEventHandler() {
      return this;
    }

  }

  interface RMStateStoreHelper {
    RMStateStore getRMStateStore() throws Exception;
    boolean isFinalStateValid() throws Exception;
  }

  void waitNotify(TestDispatcher dispatcher) {
    long startTime = System.currentTimeMillis();
    while(!dispatcher.notified) {
      synchronized (dispatcher) {
        try {
          dispatcher.wait(1000);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
      if(System.currentTimeMillis() - startTime > 1000*60) {
        fail("Timed out attempt store notification");
      }
    }
    dispatcher.notified = false;
  }

  void storeApp(RMStateStore store, ApplicationId appId, long submitTime,
      long startTime) throws Exception {
    ApplicationSubmissionContext context =
        new ApplicationSubmissionContextPBImpl();
    context.setApplicationId(appId);

    RMApp mockApp = mock(RMApp.class);
    when(mockApp.getApplicationId()).thenReturn(appId);
    when(mockApp.getSubmitTime()).thenReturn(submitTime);
    when(mockApp.getStartTime()).thenReturn(startTime);
    when(mockApp.getApplicationSubmissionContext()).thenReturn(context);
    when(mockApp.getUser()).thenReturn("test");
    store.storeNewApplication(mockApp);
  }

  ContainerId storeAttempt(RMStateStore store, ApplicationAttemptId attemptId,
      String containerIdStr, Token<AMRMTokenIdentifier> appToken,
      SecretKey clientTokenMasterKey, TestDispatcher dispatcher)
      throws Exception {

    Container container = new ContainerPBImpl();
    container.setId(ConverterUtils.toContainerId(containerIdStr));
    RMAppAttempt mockAttempt = mock(RMAppAttempt.class);
    when(mockAttempt.getAppAttemptId()).thenReturn(attemptId);
    when(mockAttempt.getMasterContainer()).thenReturn(container);
    when(mockAttempt.getAMRMToken()).thenReturn(appToken);
    when(mockAttempt.getClientTokenMasterKey())
        .thenReturn(clientTokenMasterKey);
    dispatcher.attemptId = attemptId;
    dispatcher.storedException = null;
    store.storeNewApplicationAttempt(mockAttempt);
    waitNotify(dispatcher);
    return container.getId();
  }

  void testRMAppStateStore(RMStateStoreHelper stateStoreHelper)
      throws Exception {
    long submitTime = System.currentTimeMillis();
    long startTime = System.currentTimeMillis() + 1234;
    Configuration conf = new YarnConfiguration();
    RMStateStore store = stateStoreHelper.getRMStateStore();
    TestDispatcher dispatcher = new TestDispatcher();
    store.setRMDispatcher(dispatcher);

    AMRMTokenSecretManager appTokenMgr =
        new AMRMTokenSecretManager(conf);
    ClientToAMTokenSecretManagerInRM clientToAMTokenMgr =
        new ClientToAMTokenSecretManagerInRM();

    ApplicationAttemptId attemptId1 = ConverterUtils
        .toApplicationAttemptId("appattempt_1352994193343_0001_000001");
    ApplicationId appId1 = attemptId1.getApplicationId();
    storeApp(store, appId1, submitTime, startTime);

    // create application token and client token key for attempt1
    Token<AMRMTokenIdentifier> appAttemptToken1 =
        generateAMRMToken(attemptId1, appTokenMgr);
    HashSet<Token<?>> attemptTokenSet1 = new HashSet<Token<?>>();
    attemptTokenSet1.add(appAttemptToken1);
    SecretKey clientTokenKey1 =
        clientToAMTokenMgr.createMasterKey(attemptId1);

    ContainerId containerId1 = storeAttempt(store, attemptId1,
          "container_1352994193343_0001_01_000001",
          appAttemptToken1, clientTokenKey1, dispatcher);

    String appAttemptIdStr2 = "appattempt_1352994193343_0001_000002";
    ApplicationAttemptId attemptId2 =
        ConverterUtils.toApplicationAttemptId(appAttemptIdStr2);

    // create application token and client token key for attempt2
    Token<AMRMTokenIdentifier> appAttemptToken2 =
        generateAMRMToken(attemptId2, appTokenMgr);
    HashSet<Token<?>> attemptTokenSet2 = new HashSet<Token<?>>();
    attemptTokenSet2.add(appAttemptToken2);
    SecretKey clientTokenKey2 =
        clientToAMTokenMgr.createMasterKey(attemptId2);

    ContainerId containerId2 = storeAttempt(store, attemptId2,
          "container_1352994193343_0001_02_000001",
          appAttemptToken2, clientTokenKey2, dispatcher);

    ApplicationAttemptId attemptIdRemoved = ConverterUtils
        .toApplicationAttemptId("appattempt_1352994193343_0002_000001");
    ApplicationId appIdRemoved = attemptIdRemoved.getApplicationId();
    storeApp(store, appIdRemoved, submitTime, startTime);
    storeAttempt(store, attemptIdRemoved,
        "container_1352994193343_0002_01_000001", null, null, dispatcher);

    RMApp mockRemovedApp = mock(RMApp.class);
    HashMap<ApplicationAttemptId, RMAppAttempt> attempts =
                              new HashMap<ApplicationAttemptId, RMAppAttempt>();
    ApplicationSubmissionContext context =
        new ApplicationSubmissionContextPBImpl();
    context.setApplicationId(appIdRemoved);
    when(mockRemovedApp.getSubmitTime()).thenReturn(submitTime);
    when(mockRemovedApp.getApplicationSubmissionContext()).thenReturn(context);
    when(mockRemovedApp.getAppAttempts()).thenReturn(attempts);
    RMAppAttempt mockRemovedAttempt = mock(RMAppAttempt.class);
    when(mockRemovedAttempt.getAppAttemptId()).thenReturn(attemptIdRemoved);
    attempts.put(attemptIdRemoved, mockRemovedAttempt);
    store.removeApplication(mockRemovedApp);

    // let things settle down
    Thread.sleep(1000);
    store.close();

    // load state
    store = stateStoreHelper.getRMStateStore();
    store.setRMDispatcher(dispatcher);
    RMState state = store.loadState();
    Map<ApplicationId, ApplicationState> rmAppState =
        state.getApplicationState();

    ApplicationState appState = rmAppState.get(appId1);
    // app is loaded
    assertNotNull(appState);
    // app is loaded correctly
    assertEquals(submitTime, appState.getSubmitTime());
    assertEquals(startTime, appState.getStartTime());
    // submission context is loaded correctly
    assertEquals(appId1,
                 appState.getApplicationSubmissionContext().getApplicationId());
    ApplicationAttemptState attemptState = appState.getAttempt(attemptId1);
    // attempt1 is loaded correctly
    assertNotNull(attemptState);
    assertEquals(attemptId1, attemptState.getAttemptId());
    // attempt1 container is loaded correctly
    assertEquals(containerId1, attemptState.getMasterContainer().getId());
    // attempt1 applicationToken is loaded correctly
    HashSet<Token<?>> savedTokens = new HashSet<Token<?>>();
    savedTokens.addAll(attemptState.getAppAttemptCredentials().getAllTokens());
    assertEquals(attemptTokenSet1, savedTokens);
    // attempt1 client token master key is loaded correctly
    assertArrayEquals(clientTokenKey1.getEncoded(),
        attemptState.getAppAttemptCredentials()
        .getSecretKey(RMStateStore.AM_CLIENT_TOKEN_MASTER_KEY_NAME));

    attemptState = appState.getAttempt(attemptId2);
    // attempt2 is loaded correctly
    assertNotNull(attemptState);
    assertEquals(attemptId2, attemptState.getAttemptId());
    // attempt2 container is loaded correctly
    assertEquals(containerId2, attemptState.getMasterContainer().getId());
    // attempt2 applicationToken is loaded correctly
    savedTokens.clear();
    savedTokens.addAll(attemptState.getAppAttemptCredentials().getAllTokens());
    assertEquals(attemptTokenSet2, savedTokens);
    // attempt2 client token master key is loaded correctly
    assertArrayEquals(clientTokenKey2.getEncoded(),
        attemptState.getAppAttemptCredentials()
        .getSecretKey(RMStateStore.AM_CLIENT_TOKEN_MASTER_KEY_NAME));

    //******* update application/attempt state *******//
    ApplicationState appState2 =
        new ApplicationState(appState.submitTime, appState.startTime,
          appState.context, appState.user, RMAppState.FINISHED,
          "appDiagnostics", 1234);
    appState2.attempts.putAll(appState.attempts);
    store.updateApplicationState(appState2);

    ApplicationAttemptState oldAttemptState = attemptState;
    ApplicationAttemptState newAttemptState =
        new ApplicationAttemptState(oldAttemptState.getAttemptId(),
          oldAttemptState.getMasterContainer(),
          oldAttemptState.getAppAttemptCredentials(),
          oldAttemptState.getStartTime(), RMAppAttemptState.FINISHED,
          "myTrackingUrl", "attemptDiagnostics",
          FinalApplicationStatus.SUCCEEDED);
    store.updateApplicationAttemptState(newAttemptState);
    // let things settle down
    Thread.sleep(1000);
    store.close();

    // check updated application state.
    store = stateStoreHelper.getRMStateStore();
    store.setRMDispatcher(dispatcher);
    RMState newRMState = store.loadState();
    Map<ApplicationId, ApplicationState> newRMAppState =
        newRMState.getApplicationState();
    ApplicationState updatedAppState = newRMAppState.get(appId1);
    assertEquals(appState.getAppId(),updatedAppState.getAppId());
    assertEquals(appState.getSubmitTime(), updatedAppState.getSubmitTime());
    assertEquals(appState.getStartTime(), updatedAppState.getStartTime());
    assertEquals(appState.getUser(), updatedAppState.getUser());
    // new app state fields
    assertEquals( RMAppState.FINISHED, updatedAppState.getState());
    assertEquals("appDiagnostics", updatedAppState.getDiagnostics());
    assertEquals(1234, updatedAppState.getFinishTime());

    // check updated attempt state
    ApplicationAttemptState updatedAttemptState =
        updatedAppState.getAttempt(newAttemptState.getAttemptId());
    assertEquals(oldAttemptState.getAttemptId(),
      updatedAttemptState.getAttemptId());
    assertEquals(containerId2, updatedAttemptState.getMasterContainer().getId());
    assertArrayEquals(clientTokenKey2.getEncoded(),
      updatedAttemptState.getAppAttemptCredentials().getSecretKey(
        RMStateStore.AM_CLIENT_TOKEN_MASTER_KEY_NAME));
    // new attempt state fields
    assertEquals(RMAppAttemptState.FINISHED, updatedAttemptState.getState());
    assertEquals("myTrackingUrl", updatedAttemptState.getFinalTrackingUrl());
    assertEquals("attemptDiagnostics", updatedAttemptState.getDiagnostics());
    assertEquals(FinalApplicationStatus.SUCCEEDED,
      updatedAttemptState.getFinalApplicationStatus());

    // assert store is in expected state after everything is cleaned
    assertTrue(stateStoreHelper.isFinalStateValid());

    store.close();
  }

  public void testRMDTSecretManagerStateStore(
      RMStateStoreHelper stateStoreHelper) throws Exception {
    RMStateStore store = stateStoreHelper.getRMStateStore();
    TestDispatcher dispatcher = new TestDispatcher();
    store.setRMDispatcher(dispatcher);

    // store RM delegation token;
    RMDelegationTokenIdentifier dtId1 =
        new RMDelegationTokenIdentifier(new Text("owner1"),
          new Text("renewer1"), new Text("realuser1"));
    Long renewDate1 = new Long(System.currentTimeMillis());
    int sequenceNumber = 1111;
    store.storeRMDelegationTokenAndSequenceNumber(dtId1, renewDate1,
      sequenceNumber);
    Map<RMDelegationTokenIdentifier, Long> token1 =
        new HashMap<RMDelegationTokenIdentifier, Long>();
    token1.put(dtId1, renewDate1);

    // store delegation key;
    DelegationKey key = new DelegationKey(1234, 4321 , "keyBytes".getBytes());
    HashSet<DelegationKey> keySet = new HashSet<DelegationKey>();
    keySet.add(key);
    store.storeRMDTMasterKey(key);

    RMDTSecretManagerState secretManagerState =
        store.loadState().getRMDTSecretManagerState();
    Assert.assertEquals(token1, secretManagerState.getTokenState());
    Assert.assertEquals(keySet, secretManagerState.getMasterKeyState());
    Assert.assertEquals(sequenceNumber,
        secretManagerState.getDTSequenceNumber());
  }

  private Token<AMRMTokenIdentifier> generateAMRMToken(
      ApplicationAttemptId attemptId,
      AMRMTokenSecretManager appTokenMgr) {
    AMRMTokenIdentifier appTokenId =
        new AMRMTokenIdentifier(attemptId);
    Token<AMRMTokenIdentifier> appToken =
        new Token<AMRMTokenIdentifier>(appTokenId, appTokenMgr);
    appToken.setService(new Text("appToken service"));
    return appToken;
  }
}
