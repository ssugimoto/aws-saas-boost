/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.amazon.aws.partners.saasfactory.saasboost;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.EcrException;
import software.amazon.awssdk.services.ecr.model.ImageIdentifier;
import software.amazon.awssdk.services.ecr.model.ListImagesResponse;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class OnboardingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OnboardingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> CORS = Map.of("Access-Control-Allow-Origin", "*");
    private static final String SYSTEM_API_CALL_DETAIL_TYPE = "System API Call";
    private static final String EVENT_SOURCE = "saas-boost";
    private static final String SAAS_BOOST_ENV = System.getenv("SAAS_BOOST_ENV");
    private static final String SAAS_BOOST_EVENT_BUS = System.getenv("SAAS_BOOST_EVENT_BUS");
    private static final String API_GATEWAY_HOST = System.getenv("API_GATEWAY_HOST");
    private static final String API_GATEWAY_STAGE = System.getenv("API_GATEWAY_STAGE");
    private static final String API_TRUST_ROLE = System.getenv("API_TRUST_ROLE");
    private static final String SAAS_BOOST_BUCKET = System.getenv("SAAS_BOOST_BUCKET");
    private static final String ONBOARDING_STACK_SNS = System.getenv("ONBOARDING_STACK_SNS");
    private static final String ONBOARDING_APP_STACK_SNS = System.getenv("ONBOARDING_APP_STACK_SNS");
    private static final String ONBOARDING_VALIDATION_QUEUE = System.getenv("ONBOARDING_VALIDATION_QUEUE");
    private static final String ONBOARDING_VALIDATION_DLQ = System.getenv("ONBOARDING_VALIDATION_DLQ");
    private final OnboardingServiceDAL dal;
    private final CloudFormationClient cfn;
    private final EventBridgeClient eventBridge;
    private final EcrClient ecr;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final Route53Client route53;
    private final SqsClient sqs;

    public OnboardingService() {
        LOGGER.info("Version Info: {}", Utils.version(this.getClass()));
        this.dal = new OnboardingServiceDAL();
        this.cfn = Utils.sdkClient(CloudFormationClient.builder(), CloudFormationClient.SERVICE_NAME);
        this.eventBridge = Utils.sdkClient(EventBridgeClient.builder(), EventBridgeClient.SERVICE_NAME);
        this.ecr = Utils.sdkClient(EcrClient.builder(), EcrClient.SERVICE_NAME);
        this.s3 = Utils.sdkClient(S3Client.builder(), S3Client.SERVICE_NAME);
        try {
            this.presigner = S3Presigner.builder()
                    .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                    .region(Region.of(System.getenv("AWS_REGION")))
                    .endpointOverride(new URI("https://" + s3.serviceName() + "."
                            + Region.of(System.getenv("AWS_REGION"))
                            + ".amazonaws.com")
                    ) // will break in China regions
                    .build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        this.route53 = Utils.sdkClient(Route53Client.builder(), Route53Client.SERVICE_NAME);
        this.sqs = Utils.sdkClient(SqsClient.builder(), SqsClient.SERVICE_NAME);
    }

    /**
     * Get an onboarding record by id. Integration for GET /onboarding/{id} endpoint.
     * @param event API Gateway proxy request event containing an id path parameter
     * @param context
     * @return Onboarding object for id or HTTP 404 if not found
     */
    public APIGatewayProxyResponseEvent getOnboarding(Map<String, Object> event, Context context) {
        if (Utils.warmup(event)) {
            //LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(200);
        }

        //Utils.logRequestEvent(event);
        APIGatewayProxyResponseEvent response;
        Map<String, String> params = (Map<String, String>) event.get("pathParameters");
        String onboardingId = params.get("id");
        Onboarding onboarding = dal.getOnboarding(onboardingId);
        if (onboarding != null) {
            response = new APIGatewayProxyResponseEvent()
                    .withHeaders(CORS)
                    .withStatusCode(200)
                    .withBody(Utils.toJson(onboarding));
        } else {
            response = new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(404);
        }

        return response;
    }

    /**
     * Get all onboarding records. Integration for GET /onboarding endpoint
     * @param event API Gateway proxy request event
     * @param context
     * @return List of onboarding objects
     */
    public APIGatewayProxyResponseEvent getOnboardings(Map<String, Object> event, Context context) {
        if (Utils.warmup(event)) {
            //LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(200);
        }

        //Utils.logRequestEvent(event);
        APIGatewayProxyResponseEvent response;
        List<Onboarding> onboardings;
        Map<String, String> queryParams = (Map<String, String>) event.get("queryStringParameters");
        if (queryParams != null && queryParams.containsKey("tenantId") && Utils.isNotBlank(queryParams.get("tenantId"))) {
            onboardings = Collections.singletonList(dal.getOnboardingByTenantId(queryParams.get("tenantId")));
        } else {
            onboardings = dal.getOnboardings();
        }
        response = new APIGatewayProxyResponseEvent()
                .withHeaders(CORS)
                .withStatusCode(200)
                .withBody(Utils.toJson(onboardings));

        return response;
    }

    /**
     * Update an onboarding record by id. Integration for PUT /onboarding/{id} endpoint.
     * @param event API Gateway proxy request event containing an id path parameter
     * @param context
     * @return HTTP 200 if updated, HTTP 400 on failure
     */
    public APIGatewayProxyResponseEvent updateOnboarding(Map<String, Object> event, Context context) {
        if (Utils.warmup(event)) {
            //LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(200);
        }
        //Utils.logRequestEvent(event);
        APIGatewayProxyResponseEvent response;
        Map<String, String> params = (Map<String, String>) event.get("pathParameters");
        String onboardingId = params.get("id");
        Onboarding onboarding = Utils.fromJson((String) event.get("body"), Onboarding.class);
        if (onboarding == null) {
            response = new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withHeaders(CORS)
                    .withBody(Utils.toJson(Map.of("message", "Invalid request body")));
        } else {
            if (onboarding.getId() == null || !onboarding.getId().toString().equals(onboardingId)) {
                LOGGER.error("Can't update onboarding {} at resource {}", onboarding.getId(), onboardingId);
                response = new APIGatewayProxyResponseEvent()
                        .withStatusCode(400)
                        .withHeaders(CORS)
                        .withBody(Utils.toJson(Map.of("message", "Request body must include id")));
            } else {
                onboarding = dal.updateOnboarding(onboarding);
                response = new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withHeaders(CORS)
                        .withBody(Utils.toJson(onboarding));
            }
        }

        return response;
    }

    /**
     * Delete an onboarding record by id. Integration for DELETE /onboarding/{id} endpoint.
     * @param event API Gateway proxy request event containing an id path parameter
     * @param context
     * @return HTTP 204 if deleted, HTTP 400 on failure
     */
    public APIGatewayProxyResponseEvent deleteOnboarding(Map<String, Object> event, Context context) {
        if (Utils.warmup(event)) {
            //LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(200);
        }
        //Utils.logRequestEvent(event);
        APIGatewayProxyResponseEvent response;
        Map<String, String> params = (Map<String, String>) event.get("pathParameters");
        String onboardingId = params.get("id");
        try {
            //dal.deleteOnboarding(onboardingId);
            response = new APIGatewayProxyResponseEvent()
                    .withHeaders(CORS)
                    .withStatusCode(204); // No content
        } catch (Exception e) {
            response = new APIGatewayProxyResponseEvent()
                    .withHeaders(CORS)
                    .withStatusCode(400)
                    .withBody(Utils.toJson(Map.of("message", "Failed to delete onboarding record "
                            + onboardingId)));
        }
        return response;
    }

    /**
     * Starts the tenant onboarding workflow. Integration for POST /onboarding endpoint
     * Emits an Onboarding Created event.
     * @param event API Gateway proxy request event containing an OnboardingRequest object in the request body
     * @param context
     * @return Onboarding object in a created state or HTTP 400 if the request does not contain a name
     */
    public APIGatewayProxyResponseEvent insertOnboarding(Map<String, Object> event, Context context) {
        if (Utils.warmup(event)) {
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(200);
        }
        if (Utils.isBlank(SAAS_BOOST_BUCKET)) {
            throw new IllegalStateException("Missing required environment variable SAAS_BOOST_BUCKET");
        }
        if (Utils.isBlank(SAAS_BOOST_EVENT_BUS)) {
            throw new IllegalArgumentException("Missing required environment variable SAAS_BOOST_EVENT_BUS");
        }

        final long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("OnboardingService::startOnboarding");

        Utils.logRequestEvent(event);

        // Parse the onboarding request
        OnboardingRequest onboardingRequest = Utils.fromJson((String) event.get("body"), OnboardingRequest.class);
        if (null == onboardingRequest) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withHeaders(CORS)
                    .withBody("{\"message\": \"Invalid onboarding request.\"}");
        }
        if (Utils.isBlank(onboardingRequest.getName())) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withHeaders(CORS)
                    .withBody("{\"message\": \"Tenant name is required.\"}");
        }

        // Create a new onboarding request record for a tenant
        Onboarding onboarding = new Onboarding();
        onboarding.setRequest(onboardingRequest);
        // We're using the generated onboarding id as part of the S3 key
        // so, first we need to persist the onboarding record, then we'll
        // have to update it. TODO rethink this...
        LOGGER.info("Saving new onboarding request");
        onboarding = dal.insertOnboarding(onboarding);

        // Generate the presigned URL for this tenant's ZIP archive
        final String key = "temp/" + onboarding.getId().toString() + ".zip";
        final Duration expires = Duration.ofMinutes(15); // UI times out in 10 min
        PresignedPutObjectRequest presignedObject = presigner.presignPutObject(request -> request
                .signatureDuration(expires)
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(SAAS_BOOST_BUCKET)
                        .key(key)
                        .build()
                )
                .build()
        );
        onboarding.setZipFileUrl(presignedObject.url().toString());
        onboarding = dal.updateOnboarding(onboarding);
        LOGGER.info("Updated onboarding request with S3 URL");

        // Let everyone know we've created an onboarding request so it can be validated
        Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, "saas-boost",
                OnboardingEvent.ONBOARDING_INITIATED.detailType(),
                Map.of("onboardingId", onboarding.getId())
        );

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("OnboardingService::startOnboarding exec " + totalTimeMillis);

        return new APIGatewayProxyResponseEvent()
                .withHeaders(CORS)
                .withStatusCode(200)
                .withBody(Utils.toJson(onboarding));
    }

    public void handleOnboardingEvent(Map<String, Object> event, Context context) {
        if ("saas-boost".equals(event.get("source"))) {
            OnboardingEvent onboardingEvent = OnboardingEvent.fromDetailType((String) event.get("detail-type"));
            if (onboardingEvent != null) {
                switch (onboardingEvent) {
                    case ONBOARDING_INITIATED:
                        LOGGER.info("Handling Onboarding Initiated");
                        handleOnboardingInitiated(event, context);
                        break;
                    case ONBOARDING_VALID:
                        LOGGER.info("Handling Onboarding Validated");
                        handleOnboardingValidated(event, context);
                        break;
                    case ONBOARDING_TENANT_ASSIGNED:
                        LOGGER.info("Handling Onboarding Tenant Assigned");
                        handleOnboardingTenantAssigned(event, context);
                        break;
                    case ONBOARDING_STACK_STATUS_CHANGED:
                        LOGGER.info("Handling Onboarding Stack Status Changed");
                        handleOnboardingStackStatusChanged(event, context);
                        break;
                    case ONBOARDING_BASE_PROVISIONED:
                        LOGGER.info("Handling Onboarding Base Provisioned");
                        handleOnboardingBaseProvisioned(event, context);
                        break;
                    case ONBOARDING_PROVISIONED:
                        LOGGER.info("Handling Onboarding Provisioning Complete");
                        handleOnboardingProvisioned(event, context);
                        break;
                    case ONBOARDING_DEPLOYMENT_PIPELINE_CHANGED:
                        LOGGER.info("Handling Onboarding Deployment Pipeline Changed");
                        handleOnboardingDeploymentPipelineChanged(event, context);
                        break;
                    case ONBOARDING_DEPLOYED:
                        LOGGER.info("Handling Onboarding Workloads Deployed");
                        handleOnboardingDeployed(event, context);
                        break;
                }
            } else {
                LOGGER.error("Can't find onboarding event for detail-type {}", event.get("detail-type"));
                // TODO Throw here? Would end up in DLQ.
            }
        } else if ("aws.codepipeline".equals(event.get("source"))) {
            LOGGER.info("Handling Onboarding Deployment Pipeline Changed");
            Utils.logRequestEvent(event);
            handleOnboardingDeploymentPipelineChanged(event, context);
        } else {
            LOGGER.error("Unknown event source " + event.get("source"));
            // TODO Throw here? Would end up in DLQ.
        }
    }

    protected void handleOnboardingInitiated(Map<String, Object> event, Context context) {
        if (Utils.isBlank(ONBOARDING_VALIDATION_QUEUE)) {
            throw new IllegalStateException("Missing required environment variable ONBOARDING_VALIDATION_QUEUE");
        }
        if (OnboardingEvent.validate(event)) {
            Map<String, Object> detail = (Map<String, Object>) event.get("detail");
            Onboarding onboarding = dal.getOnboarding((String) detail.get("onboardingId"));
            if (onboarding != null) {
                if (OnboardingStatus.created == onboarding.getStatus()) {
                    try {
                        // Queue this newly created onboarding request for validation
                        LOGGER.info("Publishing message to onboarding validation queue {} {}", onboarding.getId(),
                                ONBOARDING_VALIDATION_QUEUE);
                        sqs.sendMessage(request -> request
                                .queueUrl(ONBOARDING_VALIDATION_QUEUE)
                                .messageBody(Utils.toJson(Map.of("onboardingId", onboarding.getId())))
                        );
                        dal.updateStatus(onboarding.getId(), OnboardingStatus.validating);
                    } catch (SdkServiceException sqsError) {
                        LOGGER.error("sqs:SendMessage error", sqsError);
                        LOGGER.error(Utils.getFullStackTrace(sqsError));
                        throw sqsError;
                    }
                } else {
                    // Onboarding is in the wrong state for validation
                    LOGGER.error("Can not queue onboarding {} for validation with status {}", onboarding.getId(),
                            onboarding.getStatus());
                    // TODO Throw here? Would end up in DLQ.
                }
            } else {
                // Can't find an onboarding record for this id
                LOGGER.error("Can't find onboarding record for {}", detail.get("onboardingId"));
                // TODO Throw here? Would end up in DLQ.
            }
        } else {
            LOGGER.error("Missing onboardingId in event detail {}", Utils.toJson(event.get("detail")));
            // TODO Throw here? Would end up in DLQ.
        }
    }

    protected void handleOnboardingValidated(Map<String, Object> event, Context context) {
        if (Utils.isBlank(API_GATEWAY_HOST)) {
            throw new IllegalStateException("Missing required environment variable API_GATEWAY_HOST");
        }
        if (Utils.isBlank(API_GATEWAY_STAGE)) {
            throw new IllegalStateException("Missing required environment variable API_GATEWAY_STAGE");
        }
        if (Utils.isBlank(API_TRUST_ROLE)) {
            throw new IllegalStateException("Missing required environment variable API_TRUST_ROLE");
        }
        if (OnboardingEvent.validate(event)) {
            Map<String, Object> detail = (Map<String, Object>) event.get("detail");
            Onboarding onboarding = dal.getOnboarding((String) detail.get("onboardingId"));
            if (onboarding != null) {
                if (onboarding.getTenantId() != null) {
                    LOGGER.error("Unexpected validated onboarding request {} with existing tenantId"
                            , onboarding.getId());
                    // TODO throw illegal state?
                }
                if (OnboardingStatus.validating != onboarding.getStatus()) {
                    // TODO Also illegal state
                }
                onboarding = dal.updateStatus(onboarding.getId(), OnboardingStatus.validated);
                // Call the tenant service synchronously to insert the new tenant record
                LOGGER.info("Calling tenant service insert tenant API");
                LOGGER.info(Utils.toJson(onboarding.getRequest()));
                String insertTenantResponseBody = ApiGatewayHelper.signAndExecuteApiRequest(
                        ApiGatewayHelper.getApiRequest(
                                API_GATEWAY_HOST,
                                API_GATEWAY_STAGE,
                                ApiRequest.builder()
                                        .resource("tenants")
                                        .method("POST")
                                        .body(Utils.toJson(onboarding.getRequest()))
                                        .build()
                        ),
                        API_TRUST_ROLE,
                        (String) event.get("id")
                );
                Map<String, Object> insertedTenant = Utils.fromJson(insertTenantResponseBody, LinkedHashMap.class);
                if (null == insertedTenant) {
                    failOnboarding(onboarding.getId(), "Tenant insert API call failed");
                    return;
                }
                // Update the onboarding record with the new tenant id
                String tenantId = (String) insertedTenant.get("id");
                onboarding.setTenantId(UUID.fromString(tenantId));
                onboarding = dal.updateOnboarding(onboarding);
    
                // Assign a CIDR block to this tenant to use for its VPC
                try {
                    dal.assignCidrBlock(tenantId);
                } catch (Exception e) {
                    // Unexpected error since we have already validated... but eventual consistency
                    failOnboarding(onboarding.getId(), "Could not assign CIDR for tenant VPC");
                    return;
                }

                // Ready to provision the base infrastructure for this tenant
                Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, "saas-boost",
                        OnboardingEvent.ONBOARDING_TENANT_ASSIGNED.detailType(),
                        Map.of("onboardingId", onboarding.getId(), "tenant", insertedTenant));
            } else {
                // Can't find an onboarding record for this id
                LOGGER.error("Can't find onboarding record for {}", detail.get("onboardingId"));
                // TODO Throw here? Would end up in Lambda DLQ. EventBridge has already succeeded.
            }
        } else {
            LOGGER.error("Missing onboardingId in event detail {}", Utils.toJson(event.get("detail")));
            // TODO Throw here? Would end up in Lambda DLQ. EventBridge has already succeeded.
        }
    }

    protected void handleOnboardingTenantAssigned(Map<String, Object> event, Context context) {
        if (Utils.isBlank(SAAS_BOOST_ENV)) {
            throw new IllegalStateException("Missing required environment variable SAAS_BOOST_ENV");
        }
        if (Utils.isBlank(SAAS_BOOST_BUCKET)) {
            throw new IllegalArgumentException("Missing required environment variable SAAS_BOOST_BUCKET");
        }
        if (Utils.isBlank(API_GATEWAY_HOST)) {
            throw new IllegalStateException("Missing required environment variable API_GATEWAY_HOST");
        }
        if (Utils.isBlank(API_GATEWAY_STAGE)) {
            throw new IllegalStateException("Missing required environment variable API_GATEWAY_STAGE");
        }
        if (Utils.isBlank(API_TRUST_ROLE)) {
            throw new IllegalStateException("Missing required environment variable API_TRUST_ROLE");
        }
        if (Utils.isBlank(ONBOARDING_STACK_SNS)) {
            throw new IllegalArgumentException("Missing required environment variable ONBOARDING_STACK_SNS");
        }
        if (OnboardingEvent.validate(event, "tenant")) {
            Map<String, Object> detail = (Map<String, Object>) event.get("detail");
            Onboarding onboarding = dal.getOnboarding((String) detail.get("onboardingId"));
            if (onboarding != null) {
                String tenantId = onboarding.getTenantId().toString();
                Map<String, Object> tenant = (Map<String, Object>) detail.get("tenant");
                String cidrBlock = dal.getCidrBlock(onboarding.getTenantId());
                if (Utils.isBlank(cidrBlock)) {
                    // TODO rethrow to DLQ?
                    failOnboarding(onboarding.getId(), "Can't find assigned CIDR for tenant " + tenantId);
                    return;
                }
                String cidrPrefix = cidrBlock.substring(0, cidrBlock.indexOf(".", cidrBlock.indexOf(".") + 1));

                // Make a synchronous call to the settings service for the app config
                Map<String, Object> appConfig = getAppConfig(context);
                if (null == appConfig) {
                    // TODO rethrow to DLQ?
                    failOnboarding(onboarding.getId(), "Settings getAppConfig API call failed");
                    return;
                }

                // And parameters specific to this tenant
                String tenantSubdomain = Objects.toString(tenant.get("subdomain"), "");
                String tier = Objects.toString(tenant.get("tier"), "default");

                String domainName = Objects.toString(appConfig.get("domainName"), "");
                String hostedZone = Objects.toString(appConfig.get("hostedZone"), "");
                String sslCertificateArn = Objects.toString(appConfig.get("sslCertificate"), "");

                List<Parameter> templateParameters = new ArrayList<>();
                templateParameters.add(Parameter.builder().parameterKey("Environment").parameterValue(SAAS_BOOST_ENV).build());
                templateParameters.add(Parameter.builder().parameterKey("DomainName").parameterValue(domainName).build());
                templateParameters.add(Parameter.builder().parameterKey("HostedZoneId").parameterValue(hostedZone).build());
                templateParameters.add(Parameter.builder().parameterKey("SSLCertificateArn").parameterValue(sslCertificateArn).build());
                templateParameters.add(Parameter.builder().parameterKey("TenantId").parameterValue(tenantId).build());
                templateParameters.add(Parameter.builder().parameterKey("TenantSubDomain").parameterValue(tenantSubdomain).build());
                templateParameters.add(Parameter.builder().parameterKey("CidrPrefix").parameterValue(cidrPrefix).build());
                templateParameters.add(Parameter.builder().parameterKey("Tier").parameterValue(tier).build());

                for (Parameter p : templateParameters) {
                    if (p.parameterValue() == null) {
                        LOGGER.error("OnboardingService::provisionTenant template parameter {} is NULL",
                                p.parameterKey());
                        failOnboarding(onboarding.getId(), "CloudFormation template parameter "
                                + p.parameterKey() + " is NULL");
                        throw new RuntimeException();
                    }
                }

                String tenantShortId = tenantId.toString().substring(0, 8);
                String stackName = "sb-" + SAAS_BOOST_ENV + "-tenant-" + tenantShortId;

                // Now run the onboarding stack to provision the infrastructure for this tenant
                LOGGER.info("OnboardingService::provisionTenant create stack " + stackName);
                String stackId;
                try {
                    CreateStackResponse cfnResponse = cfn.createStack(CreateStackRequest.builder()
                            .stackName(stackName)
                            .disableRollback(true) // This was set to DO_NOTHING to ease debugging of failed stacks. Maybe not appropriate for "production". If we change this we'll have to add a whole bunch of IAM delete permissions to the execution role.
                            .capabilitiesWithStrings("CAPABILITY_NAMED_IAM", "CAPABILITY_AUTO_EXPAND")
                            .notificationARNs(ONBOARDING_STACK_SNS)
                            .templateURL("https://" + SAAS_BOOST_BUCKET + ".s3.amazonaws.com/tenant-onboarding.yaml")
                            .parameters(templateParameters)
                            .build()
                    );
                    stackId = cfnResponse.stackId();
                    onboarding.setStatus(OnboardingStatus.provisioning);
                    onboarding.addStack(new OnboardingStack(stackName, stackId, true, "CREATE_IN_PROGRESS"));
                    dal.updateOnboarding(onboarding);
                    LOGGER.info("OnboardingService::provisionTenant stack id " + stackId);
                    Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, EVENT_SOURCE,
                            "Tenant Onboarding Status Changed",
                            Map.of(
                                    "tenantId", tenantId,
                                    "onboardingStatus", "provisioning"
                            )
                    );
                } catch (SdkServiceException cfnError) {
                    LOGGER.error("cloudformation::createStack failed {}", cfnError.getMessage());
                    LOGGER.error(Utils.getFullStackTrace(cfnError));
                    failOnboarding(onboarding.getId(), cfnError.getMessage());
                    throw cfnError;
                }
            } else {
                // Can't find an onboarding record for this id
                LOGGER.error("Can't find onboarding record for {}", detail.get("onboardingId"));
                // TODO Throw here? Would end up in DLQ.
            }
        } else {
            LOGGER.error("Missing onboardingId in event detail {}", Utils.toJson(event.get("detail")));
            // TODO Throw here? Would end up in DLQ.
        }
    }

    protected void handleOnboardingStackStatusChanged(Map<String, Object> event, Context context) {
        // TODO stack events don't have the onboardingId, so we can't use OnboardingEvent::validate as written
        Map<String, Object> detail = (Map<String, Object>) event.get("detail");
        if (detail != null && detail.containsKey("tenantId") && detail.containsKey("stackId")
                && detail.containsKey("stackStatus")) {
            String tenantId = (String) detail.get("tenantId");
            String stackId = (String) detail.get("stackId");
            String stackStatus = (String) detail.get("stackStatus");
            OnboardingStatus status = OnboardingStatus.fromStackStatus(stackStatus);

            Onboarding onboarding = dal.getOnboardingByTenantId(tenantId);
            if (onboarding != null) {
                LOGGER.info("Updating onboarding stack status {} {}", onboarding.getId(), stackId);
                for (OnboardingStack stack : onboarding.getStacks()) {
                    if (stackId.equals(stack.getArn())) {
                        if (!stackStatus.equals(stack.getStatus())) {
                            LOGGER.info("Stack status changing from {} to {}", stack.getStatus(), stackStatus);
                            stack.setStatus(stackStatus);
                        }
                        if (status != onboarding.getStatus()) {
                            onboarding.setStatus(status);
                            LOGGER.info("Onboarding status changing from {} to {}", onboarding.getStatus(), status);
                        }
                        dal.updateOnboarding(onboarding);
                        if (stack.isComplete()) {
                            if (stack.isBaseStack() && onboarding.baseStacksComplete()) {
                                LOGGER.info("Onboarding base stacks provisioned!");
                                Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, "saas-boost",
                                        OnboardingEvent.ONBOARDING_BASE_PROVISIONED.detailType(),
                                        Map.of("onboardingId", onboarding.getId())
                                );
                            } else if (onboarding.stacksComplete()) {
                                LOGGER.info("All onboarding stacks provisioned!");
                                Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, "saas-boost",
                                        OnboardingEvent.ONBOARDING_PROVISIONED.detailType(),
                                        Map.of("onboardingId", onboarding.getId())
                                );
                            }
                        }
                        break;
                    }
                }
                // TODO what about DELETE_COMPLETE stack status? Do we need to fire an event?
            } else {
                // Can't find an onboarding record for this id
                LOGGER.error("Can't find onboarding record for {}", detail.get("onboardingId"));
                // TODO Throw here? Would end up in DLQ.
            }
        } else {
            LOGGER.error("Missing onboardingId in event detail {}", Utils.toJson(event.get("detail")));
            // TODO Throw here? Would end up in DLQ.
        }
    }

    protected void handleOnboardingBaseProvisioned(Map<String, Object> event, Context context) {
        if (Utils.isBlank(SAAS_BOOST_ENV)) {
            throw new IllegalStateException("Missing required environment variable SAAS_BOOST_ENV");
        }
        if (Utils.isBlank(API_GATEWAY_HOST)) {
            throw new IllegalStateException("Missing required environment variable API_GATEWAY_HOST");
        }
        if (Utils.isBlank(API_GATEWAY_STAGE)) {
            throw new IllegalStateException("Missing required environment variable API_GATEWAY_STAGE");
        }
        if (Utils.isBlank(API_TRUST_ROLE)) {
            throw new IllegalStateException("Missing required environment variable API_TRUST_ROLE");
        }
        if (Utils.isBlank(ONBOARDING_APP_STACK_SNS)) {
            throw new IllegalStateException("Missing required environment variable ONBOARDING_APP_STACK_SNS");
        }

        //Utils.logRequestEvent(event);
        if (OnboardingEvent.validate(event)) {
            Map<String, Object> detail = (Map<String, Object>) event.get("detail");
            Onboarding onboarding = dal.getOnboarding((String) detail.get("onboardingId"));
            if (onboarding != null) {
                String tenantId = onboarding.getTenantId().toString();
                Map<String, Object> tenant = getTenant(tenantId, context);
                // TODO tenant == null means tenant API call failed? retry?
                if (tenant != null) {
                    Map<String, Object> appConfig = getAppConfig(context);
                    if (null == appConfig) {
                        LOGGER.error("Settings get app config API call failed");
                        // TODO retry?
                    }

                    String applicationName = (String) appConfig.get("name");
                    String vpc;
                    String privateSubnetA;
                    String privateSubnetB;
                    String ecsSecurityGroup;
                    String loadBalancerArn;
                    String httpListenerArn;
                    String httpsListenerArn = ""; // might not have an HTTPS listener if they don't have an SSL certificate
                    String ecsCluster;
                    String fsxDns;
                    Map<String, Map<String, String>> tenantResources = (Map<String, Map<String, String>>) tenant.get("resources");
                    try {
                        vpc = tenantResources.get("VPC").get("name");
                        privateSubnetA = tenantResources.get("PRIVATE_SUBNET_A").get("name");
                        privateSubnetB = tenantResources.get("PRIVATE_SUBNET_B").get("name");
                        ecsCluster = tenantResources.get("ECS_CLUSTER").get("name");
                        ecsSecurityGroup = tenantResources.get("ECS_SECURITY_GROUP").get("name");
                        loadBalancerArn = tenantResources.get("LOAD_BALANCER").get("arn");
                        httpListenerArn = tenantResources.get("HTTP_LISTENER").get("arn");
                        if (tenantResources.containsKey("HTTPS_LISTENER")) {
                            httpsListenerArn = Objects.toString(tenantResources.get("HTTPS_LISTENER").get("arn"), "");
                        }
                        if (Utils.isBlank(vpc) || Utils.isBlank(privateSubnetA) || Utils.isBlank(privateSubnetB)
                                || Utils.isBlank(ecsCluster) || Utils.isBlank(ecsSecurityGroup) || Utils.isBlank(loadBalancerArn)
                                || Utils.isBlank(httpListenerArn)) { // OK if HTTPS listener is blank
                            LOGGER.error("Missing required tenant environment resources");
                            failOnboarding(onboarding.getId(), "Missing required tenant environment resources");
                            return;
                        }
                    } catch (Exception e) {
                        LOGGER.error("Error parsing tenant resources", e);
                        LOGGER.error(Utils.getFullStackTrace(e));
                        failOnboarding(onboarding.getId(), "Error parsing resources for tenant " + tenantId);
                        return;
                    }

                    String tier = (String) tenant.get("tier");
                    if (Utils.isBlank(tier)) {
                        LOGGER.error("Tenant is missing tier");
                        failOnboarding(onboarding.getId(), "Error retrieving tier for tenant " + tenantId);
                        return;
                    }

                    List<String> applicationServiceStacks = new ArrayList<>();

                    Map<String, Integer> pathPriority = getPathPriority(appConfig);
                    Properties serviceDiscovery = new Properties();

                    Map<String, Object> services = (Map<String, Object>) appConfig.get("services");
                    for (Map.Entry<String, Object> serviceConfig : services.entrySet()) {
                        String serviceName = serviceConfig.getKey();
                        // CloudFormation resource names can only contain alpha numeric characters or a dash
                        String serviceResourceName = serviceName.replaceAll("[^0-9A-Za-z-]", "").toLowerCase();
                        Map<String, Object> service = (Map<String, Object>) serviceConfig.getValue();
                        Boolean isPublic = (Boolean) service.get("public");
                        String pathPart = (isPublic) ? (String) service.get("path") : "";
                        Integer publicPathRulePriority = (isPublic) ? pathPriority.get(serviceName) : 0;
                        String healthCheck = (String) service.get("healthCheckUrl");

                        // CloudFormation won't let you use dashes or underscores in Mapping second level key names
                        // And it won't let you use Fn::Join or Fn::Split in Fn::FindInMap... so we will mangle this
                        // parameter before we send it in.
                        String clusterOS = ((String) service.getOrDefault("operatingSystem", ""))
                                .replace("_", "");

                        Integer containerPort = (Integer) service.get("containerPort");
                        String containerRepo = (String) service.get("containerRepo");
                        String imageTag = (String) service.getOrDefault("containerTag", "latest");

                        // If there are any private services, we will create an environment variables called
                        // SERVICE_<SERVICE_NAME>_HOST and SERVICE_<SERVICE_NAME>_PORT to pass to the task definitions
                        String serviceEnvName = Utils.toUpperSnakeCase(serviceName);
                        String serviceHost = "SERVICE_" + serviceEnvName + "_HOST";
                        String servicePort = "SERVICE_" + serviceEnvName + "_PORT";
                        if (!isPublic) {
                            LOGGER.debug("Creating service discovery environment variables {}, {}", serviceHost, servicePort);
                            serviceDiscovery.put(serviceHost, serviceResourceName + ".local");
                            serviceDiscovery.put(servicePort, Objects.toString(containerPort));
                        }

                        Map<String, Object> tiers = (Map<String, Object>) service.get("tiers");
                        if (!tiers.containsKey(tier)) {
                            LOGGER.error("Missing tier '{}' definition for tenant {}", tier, tenantId);
                            failOnboarding(onboarding.getId(), "Error retrieving tier for tenant " + tenantId);
                            return;
                        }

                        Map<String, Object> tierConfig = (Map<String, Object>) tiers.get(tier);
                        String clusterInstanceType = (String) tierConfig.get("instanceType");
                        Integer taskMemory = (Integer) tierConfig.get("memory");
                        Integer taskCpu = (Integer) tierConfig.get("cpu");
                        Integer minCount = (Integer) tierConfig.get("min");
                        Integer maxCount = (Integer) tierConfig.get("max");

                        // Does this service use a shared filesystem?
                        Boolean enableEfs = Boolean.FALSE;
                        Boolean enableFSx = Boolean.FALSE;
                        String mountPoint = "";
                        Boolean encryptFilesystem = Boolean.FALSE;
                        String filesystemLifecycle = "NEVER";
                        String fileSystemType = "";
                        Integer fsxStorageGb = 0;
                        Integer fsxThroughputMbs = 0;
                        Integer fsxBackupRetentionDays = 7;
                        String fsxDailyBackupTime = "";
                        String fsxWeeklyMaintenanceTime = "";
                        String fsxWindowsMountDrive = "";
                        Map<String, Object> filesystem = (Map<String, Object>) tierConfig.get("filesystem");
                        if (filesystem != null && !filesystem.isEmpty()) {
                            fileSystemType = (String) filesystem.get("fileSystemType");
                            mountPoint = (String) filesystem.get("mountPoint");
                            if ("EFS".equals(fileSystemType)) {
                                enableEfs = Boolean.TRUE;
                                Map<String, Object> efsConfig = (Map<String, Object>) filesystem.get("efs");
                                encryptFilesystem = (Boolean) efsConfig.get("encryptAtRest");
                                filesystemLifecycle = (String) efsConfig.get("filesystemLifecycle");
                            } else if ("FSX".equals(fileSystemType)) {
                                enableFSx = Boolean.TRUE;
                                Map<String, Object> fsxConfig = (Map<String, Object>) filesystem.get("fsx");
                                fsxStorageGb = (Integer) fsxConfig.get("storageGb"); // GB 32 to 65,536
                                fsxThroughputMbs = (Integer) fsxConfig.get("throughputMbs"); // MB/s
                                fsxBackupRetentionDays = (Integer) fsxConfig.get("backupRetentionDays"); // 7 to 35
                                fsxDailyBackupTime = (String) fsxConfig.get("dailyBackupTime"); //HH:MM in UTC
                                fsxWeeklyMaintenanceTime = (String) fsxConfig.get("weeklyMaintenanceTime");//d:HH:MM in UTC
                                fsxWindowsMountDrive = (String) fsxConfig.get("windowsMountDrive");
                            }
                        }

                        // Does this service use a relational database?
                        Boolean enableDatabase = Boolean.FALSE;
                        String dbInstanceClass = "";
                        String dbEngine = "";
                        String dbVersion = "";
                        String dbFamily = "";
                        String dbMasterUsername = "";
                        String dbMasterPasswordRef = "";
                        Integer dbPort = -1;
                        String dbDatabase = "";
                        String dbBootstrap = "";
                        Map<String, Object> database = (Map<String, Object>) tierConfig.get("database");
                        if (database != null && !database.isEmpty()) {
                            enableDatabase = Boolean.TRUE;
                            dbEngine = (String) database.get("engine");
                            dbVersion = (String) database.get("version");
                            dbFamily = (String) database.get("family");
                            dbInstanceClass = (String) database.get("instanceClass");
                            dbMasterUsername = (String) database.get("username");
                            dbPort = (Integer) database.get("port");
                            dbDatabase = (String) database.get("database");
                            // TODO fix this dbBootstrap = (String) database.get("DB_BOOTSTRAP_FILE");
                            //dbMasterPasswordRef = (String) database.get("password");
                            dbMasterPasswordRef = "/saas-boost/" + SAAS_BOOST_ENV + "/DB_MASTER_PASSWORD";
                        }

                        List<Parameter> templateParameters = new ArrayList<>();
    //        templateParameters.add(Parameter.builder().parameterKey("SaaSBoostBucket").parameterValue(settings.get("SAAS_BOOST_BUCKET")).build());
    //        templateParameters.add(Parameter.builder().parameterKey("LambdaSourceFolder").parameterValue(settings.get("SAAS_BOOST_LAMBDAS_FOLDER")).build());
    //        templateParameters.add(Parameter.builder().parameterKey("ArtifactBucket").parameterValue(settings.get("CODE_PIPELINE_BUCKET")).build());
    //        templateParameters.add(Parameter.builder().parameterKey("ALBAccessLogsBucket").parameterValue(settings.get("ALB_ACCESS_LOGS_BUCKET")).build());
    //        templateParameters.add(Parameter.builder().parameterKey("BillingPlan").parameterValue(billingPlan).build());
    //        templateParameters.add(Parameter.builder().parameterKey("CodePipelineRoleArn").parameterValue(settings.get("CODE_PIPELINE_ROLE")).build());

                        templateParameters.add(Parameter.builder().parameterKey("Environment").parameterValue(SAAS_BOOST_ENV).build());
                        templateParameters.add(Parameter.builder().parameterKey("TenantId").parameterValue(tenantId.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("ServiceName").parameterValue(serviceName).build());
                        templateParameters.add(Parameter.builder().parameterKey("ServiceResourceName").parameterValue(serviceResourceName).build());
                        templateParameters.add(Parameter.builder().parameterKey("ContainerRepository").parameterValue(containerRepo).build());
                        templateParameters.add(Parameter.builder().parameterKey("ContainerRepositoryTag").parameterValue(imageTag).build());
                        templateParameters.add(Parameter.builder().parameterKey("ECSCluster").parameterValue(ecsCluster).build());
                        templateParameters.add(Parameter.builder().parameterKey("PubliclyAddressable").parameterValue(isPublic.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("PublicPathRoute").parameterValue(pathPart).build());
                        templateParameters.add(Parameter.builder().parameterKey("PublicPathRulePriority").parameterValue(publicPathRulePriority.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("VPC").parameterValue(vpc).build());
                        templateParameters.add(Parameter.builder().parameterKey("SubnetPrivateA").parameterValue(privateSubnetA).build());
                        templateParameters.add(Parameter.builder().parameterKey("SubnetPrivateB").parameterValue(privateSubnetB).build());
                        templateParameters.add(Parameter.builder().parameterKey("ECSLoadBalancer").parameterValue(loadBalancerArn).build());
                        templateParameters.add(Parameter.builder().parameterKey("ECSLoadBalancerHttpListener").parameterValue(httpListenerArn).build());
                        templateParameters.add(Parameter.builder().parameterKey("ECSLoadBalancerHttpsListener").parameterValue(httpsListenerArn).build());
                        templateParameters.add(Parameter.builder().parameterKey("ECSSecurityGroup").parameterValue(ecsSecurityGroup).build());
                        templateParameters.add(Parameter.builder().parameterKey("ContainerOS").parameterValue(clusterOS).build());
                        templateParameters.add(Parameter.builder().parameterKey("ClusterInstanceType").parameterValue(clusterInstanceType).build());
                        templateParameters.add(Parameter.builder().parameterKey("TaskMemory").parameterValue(taskMemory.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("TaskCPU").parameterValue(taskCpu.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("MinTaskCount").parameterValue(minCount.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("MaxTaskCount").parameterValue(maxCount.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("ContainerPort").parameterValue(containerPort.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("ContainerHealthCheckPath").parameterValue(healthCheck).build());
                        templateParameters.add(Parameter.builder().parameterKey("UseEFS").parameterValue(enableEfs.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("MountPoint").parameterValue(mountPoint).build());
                        templateParameters.add(Parameter.builder().parameterKey("EncryptEFS").parameterValue(encryptFilesystem.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("EFSLifecyclePolicy").parameterValue(filesystemLifecycle).build());
                        templateParameters.add(Parameter.builder().parameterKey("UseFSx").parameterValue(enableFSx.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("FSxWindowsMountDrive").parameterValue(fsxWindowsMountDrive).build());
                        templateParameters.add(Parameter.builder().parameterKey("FSxDailyBackupTime").parameterValue(fsxDailyBackupTime).build());
                        templateParameters.add(Parameter.builder().parameterKey("FSxBackupRetention").parameterValue(fsxBackupRetentionDays.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("FSxThroughputCapacity").parameterValue(fsxThroughputMbs.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("FSxStorageCapacity").parameterValue(fsxStorageGb.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("FSxWeeklyMaintenanceTime").parameterValue(fsxWeeklyMaintenanceTime).build());
                        templateParameters.add(Parameter.builder().parameterKey("UseRDS").parameterValue(enableDatabase.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("RDSInstanceClass").parameterValue(dbInstanceClass).build());
                        templateParameters.add(Parameter.builder().parameterKey("RDSEngine").parameterValue(dbEngine).build());
                        templateParameters.add(Parameter.builder().parameterKey("RDSEngineVersion").parameterValue(dbVersion).build());
                        templateParameters.add(Parameter.builder().parameterKey("RDSParameterGroupFamily").parameterValue(dbFamily).build());
                        templateParameters.add(Parameter.builder().parameterKey("RDSMasterUsername").parameterValue(dbMasterUsername).build());
                        templateParameters.add(Parameter.builder().parameterKey("RDSMasterPasswordParam").parameterValue(dbMasterPasswordRef).build());
                        templateParameters.add(Parameter.builder().parameterKey("RDSPort").parameterValue(dbPort.toString()).build());
                        templateParameters.add(Parameter.builder().parameterKey("RDSDatabase").parameterValue(dbDatabase).build());
                        templateParameters.add(Parameter.builder().parameterKey("RDSBootstrap").parameterValue(dbBootstrap).build());
                        // TODO rework these last 2?
                        templateParameters.add(Parameter.builder().parameterKey("MetricsStream").parameterValue("").build());
                        templateParameters.add(Parameter.builder().parameterKey("EventBus").parameterValue(SAAS_BOOST_EVENT_BUS).build());
                        for (Parameter p : templateParameters) {
                            //LOGGER.info("{} => {}", p.parameterKey(), p.parameterValue());
                            if (p.parameterValue() == null) {
                                LOGGER.error("OnboardingService::provisionTenant template parameter {} is NULL", p.parameterKey());
                                dal.updateStatus(onboarding.getId(), OnboardingStatus.failed);
                                // TODO throw here?
                                throw new RuntimeException("CloudFormation template parameter " + p.parameterKey() + " is NULL");
                            }
                        }

                        // Make the stack name look like what CloudFormation would have done for a nested stack
                        String tenantShortId = tenantId.toString().substring(0, 8);
                        String stackName = "sb-" + SAAS_BOOST_ENV + "-tenant-" + tenantShortId + "-app-" + serviceResourceName
                                + "-" + Utils.randomString(12).toUpperCase();
                        if (stackName.length() > 128) {
                            stackName = stackName.substring(0, 128);
                        }
                        // Now run the onboarding stack to provision the infrastructure for this application service
                        LOGGER.info("OnboardingService::provisionApplication create stack " + stackName);

                        String stackId;
                        try {
                            CreateStackResponse cfnResponse = cfn.createStack(CreateStackRequest.builder()
                                    .stackName(stackName)
                                    .disableRollback(true)
                                    //.onFailure("DO_NOTHING") // This was set to DO_NOTHING to ease debugging of failed stacks. Maybe not appropriate for "production". If we change this we'll have to add a whole bunch of IAM delete permissions to the execution role.
                                    //.timeoutInMinutes(60) // Some resources can take a really long time to light up. Do we want to specify this?
                                    .capabilitiesWithStrings("CAPABILITY_NAMED_IAM", "CAPABILITY_AUTO_EXPAND")
                                    .notificationARNs(ONBOARDING_APP_STACK_SNS)
                                    .templateURL("https://" + SAAS_BOOST_BUCKET + ".s3.amazonaws.com/tenant-onboarding-app.yaml")
                                    .parameters(templateParameters)
                                    .build()
                            );
                            stackId = cfnResponse.stackId();
                            onboarding.setStatus(OnboardingStatus.provisioning);
                            onboarding.addStack(new OnboardingStack(stackName, stackId, false, "CREATE_IN_PROGRESS"));
                            onboarding = dal.updateOnboarding(onboarding);
                            LOGGER.info("OnboardingService::provisionApplication stack id " + stackId);
                            applicationServiceStacks.add(stackId);
                        } catch (CloudFormationException cfnError) {
                            LOGGER.error("cloudformation::createStack failed", cfnError);
                            LOGGER.error(Utils.getFullStackTrace(cfnError));
                            failOnboarding(onboarding.getId(), cfnError.awsErrorDetails().errorMessage());
                            return;
                        }
                    }

                    if (!serviceDiscovery.isEmpty()) {
                        String environmentFile = "tenants/" + tenantId.toString() + "/ServiceDiscovery.env";
                        ByteArrayOutputStream environmentFileContents = new ByteArrayOutputStream();
                        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                                environmentFileContents, StandardCharsets.UTF_8)
                        )) {
                            serviceDiscovery.store(writer, null);
                            s3.putObject(request -> request
                                            .bucket(SAAS_BOOST_BUCKET)
                                            .key(environmentFile)
                                            .build(),
                                    RequestBody.fromBytes(environmentFileContents.toByteArray())
                            );
                        } catch (S3Exception s3Error) {
                            LOGGER.error("Error putting service discovery file to S3");
                            LOGGER.error(Utils.getFullStackTrace(s3Error));
                            failOnboarding(onboarding.getId(), s3Error.awsErrorDetails().errorMessage());
                            return;
                        } catch (IOException ioe) {
                            LOGGER.error("Error writing service discovery data to output stream");
                            LOGGER.error(Utils.getFullStackTrace(ioe));
                            failOnboarding(onboarding.getId(), "Error writing service discovery data to output stream");
                            return;
                        }
                    }
                } else {
                    LOGGER.error("Can't parse get tenant api response");
                    failOnboarding(onboarding.getId(), "Can't fetch tenant " + tenantId);
                }
            } else {
                LOGGER.error("No onboarding record for {}", detail.get("onboardingId"));
            }
        } else {
            LOGGER.error("Missing onboardingId in event detail {}", Utils.toJson(event.get("detail")));
            // TODO Throw here? Would end up in DLQ.
        }
    }

    protected void handleOnboardingProvisioned(Map<String, Object> event, Context context) {
        // Provisioning is complete so we can deploy the workloads. Doing this after all stacks have finished
        // instead of as each non base stack finishes because until all services are up and ready the tenant
        // can't use the solution.
        if (OnboardingEvent.validate(event)) {
            Map<String, Object> detail = (Map<String, Object>) event.get("detail");
            Onboarding onboarding = dal.getOnboarding((String) detail.get("onboardingId"));
            if (onboarding != null) {
                LOGGER.info("Triggering deployment pipelines for tenant {}", onboarding.getTenantId());

                // Publish a deployment event for each of the configured services in appConfig
                Map<String, Object> appConfig = getAppConfig(context);
                Map<String, Object> services = (Map<String, Object>) appConfig.get("services");
                for (Map.Entry<String, Object> serviceConfig : services.entrySet()) {
                    Map<String, Object> service = (Map<String, Object>) serviceConfig.getValue();
                    Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, EVENT_SOURCE,
                            "Workload Ready For Deployment",
                            Map.of(
                                    "tenantId", onboarding.getTenantId(),
                                    "repository-name", service.get("containerRepo"),
                                    "image-tag", service.get("containerTag")
                            )
                    );
                }
            } else {
                LOGGER.error("Can't find onboarding record for {}", detail.get("onboardingId"));
            }
        } else {
            LOGGER.error("Missing onboardingId in event detail {}", Utils.toJson(event.get("detail")));
        }
    }

    protected void handleOnboardingDeploymentPipelineChanged(Map<String, Object> event, Context context) {
        if ("aws.codepipeline".equals(event.get("source"))) {
            Map<String, Object> detail = (Map<String, Object>) event.get("detail");
            String pipeline = (String) detail.get("pipeline");
            String prefix = "sb-" + SAAS_BOOST_ENV + "-tenant-";
            if (pipeline != null && pipeline.startsWith(prefix)) {
                // CodePipelines are named sb-${environment}-tenant-${tenantId prefix}-${service name}
                // We can fetch the full tenantId from the Tags on the pipeline if this is too fragile
                String tenantId;
                try {
                    tenantId = pipeline.split("-")[3];
                } catch (IndexOutOfBoundsException iob) {
                    LOGGER.error("Unexpected CodePipeline name pattern {}", pipeline);
                    tenantId = pipeline.substring(prefix.length());
                }
                Onboarding onboarding = dal.getOnboardingByTenantId(tenantId);
                if (onboarding != null) {
                    tenantId = onboarding.getTenantId().toString();

                    Object pipelineState = detail.get("state");
                    if ("STARTED".equals(pipelineState)) {
                        dal.updateStatus(onboarding.getId(), OnboardingStatus.deploying);
                    } else if ("FAILED".equals(pipelineState) || "CANCELED".equals(pipelineState)) {
                        // TODO how do we track this? No op? Retry?
                        // When the pipeline is created it is automatically started (there's no way to prevent this)
                        // and will fail because the source for the pipeline is not available when it's created. Even
                        // if we made the source available (the docker image to deploy), there's no guarantee that the
                        // container infrastructure would be ready yet. We trigger the first run of the pipeline after
                        // all of the infrastructure is provisioned.
                        Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, EVENT_SOURCE,
                                "Tenant Onboarding Status Changed",
                                Map.of(
                                        "tenantId", tenantId,
                                        "onboardingStatus", "failed"
                                )
                        );
                    } else if ("SUCCEEDED".equals(pipelineState)) {
                        // TODO need to track all pipelines to know whether the entire solution is deployed
                        Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, EVENT_SOURCE,
                                "Tenant Onboarding Status Changed",
                                Map.of(
                                        "tenantId", tenantId,
                                        "onboardingStatus", "succeeded"
                                )
                        );
                    }
                } else {
                    LOGGER.error("Can't find onboarding record for tenant {}", tenantId);
                }
            }
        }
    }

    protected void handleOnboardingDeployed(Map<String, Object> event, Context context) {

    }

    protected void handleOnboardingFailed(Map<String, Object> event, Context context) {

    }

    public SQSBatchResponse processValidateOnboardingQueue(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> retry = new ArrayList<>();
        List<SQSEvent.SQSMessage> fatal = new ArrayList<>();
        sqsMessageLoop:
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            String messageId = message.getMessageId();
            String messageBody = message.getBody();

            LinkedHashMap<String, Object> detail = Utils.fromJson(messageBody, LinkedHashMap.class);
            String onboardingId = (String) detail.get("onboardingId");
            LOGGER.info("Processing onboarding validation for {}", onboardingId);
            Onboarding onboarding = dal.getOnboarding(onboardingId);
            OnboardingRequest onboardingRequest = onboarding.getRequest();
            if (onboardingRequest == null) {
                LOGGER.error("No onboarding request data for {}", onboardingId);
                fatal.add(message);
                failOnboarding(onboardingId, "Onboarding record has no request content");
                continue;
            } else if (OnboardingStatus.validating != onboarding.getStatus()) {
                LOGGER.warn("Onboarding in unexpected state for validation {} {}", onboardingId
                        , onboarding.getStatus());
                fatal.add(message);
                failOnboarding(onboardingId, "Onboarding can't be validated when in state "
                        + onboarding.getStatus());
                continue;
            } else {
                Map<String, Object> appConfig = getAppConfig(context);
                // Check to see if there are any images in the ECR repo before allowing onboarding
                Map<String, Object> services = (Map<String, Object>) appConfig.get("services");
                if (services.isEmpty()) {
                    LOGGER.warn("No application services defined in AppConfig");
                    retry.add(SQSBatchResponse.BatchItemFailure.builder()
                            .withItemIdentifier(messageId)
                            .build()
                    );
                    continue;
                } else {
                    int missingImages = 0;
                    for (Map.Entry<String, Object> serviceConfig : services.entrySet()) {
                        String serviceName = serviceConfig.getKey();
                        Map<String, Object> service = (Map<String, Object>) serviceConfig.getValue();
                        String ecrRepo = (String) service.get("containerRepo");
                        String imageTag = (String) service.getOrDefault("containerTag", "latest");
                        if (Utils.isNotBlank(ecrRepo)) {
                            try {
                                ListImagesResponse dockerImages = ecr.listImages(request -> request
                                        .repositoryName(ecrRepo));
                                boolean imageAvailable = false;
                                //ListImagesResponse::hasImageIds will return true if the imageIds object is not null
                                if (dockerImages.hasImageIds()) {
                                    for (ImageIdentifier image : dockerImages.imageIds()) {
                                        if (imageTag.equals(image.imageTag())) {
                                            imageAvailable = true;
                                            break;
                                        }
                                    }
                                }
                                if (!imageAvailable) {
                                    // Not valid yet, no container image to deploy
                                    LOGGER.warn("Application Service {} does not have an available image tagged {}",
                                            serviceName, imageTag);
                                    missingImages++;
                                }
                            } catch (EcrException ecrError) {
                                LOGGER.error("ecr:ListImages error", ecrError.getMessage());
                                LOGGER.error(Utils.getFullStackTrace(ecrError));
                                // TODO do we bail here or retry?
                                failOnboarding(onboardingId, "Can't list images from ECR "
                                        + ecrError.awsErrorDetails().errorMessage());
                                fatal.add(message);
                                continue sqsMessageLoop;
                            }
                        } else {
                            // TODO no repo defined for this service yet...
                            LOGGER.warn("Application Service {} does not have a container image repository defined"
                                    , serviceName);
                            missingImages++;
                        }
                    }
                    if (missingImages > 0) {
                        retry.add(SQSBatchResponse.BatchItemFailure.builder()
                                .withItemIdentifier(messageId)
                                .build()
                        );
                        continue;
                    }
                    // Do we have any CIDR blocks left for a new tenant VPC
                    if (!dal.availableCidrBlock()) {
                        LOGGER.error("No CIDR blocks available for new VPC");
                        failOnboarding(onboardingId, "No CIDR blocks available for new VPC");
                        fatal.add(message);
                        continue;
                    }

                    // Make sure we're using a unique subdomain per tenant
                    String subdomain = onboardingRequest.getSubdomain();
                    if (Utils.isNotBlank(subdomain)) {
                        String hostedZoneId = (String) appConfig.get("hostedZone");
                        String domainName = (String) appConfig.get("domainName");
                        if (Utils.isBlank(hostedZoneId) || Utils.isBlank(domainName)) {
                            LOGGER.error("Can't onboard a subdomain without domain name and hosted zone");
                            failOnboarding(onboardingId, "Can't define tenant subdomain " + subdomain
                                    + " without a domain name and hosted zone.");
                            fatal.add(message);
                            continue;
                        } else {
                            // Ask Route53 for all the records of this hosted zone
                            try {
                                ListResourceRecordSetsResponse recordSets = route53.listResourceRecordSets(r -> r
                                        .hostedZoneId(hostedZoneId)
                                );
                                if (recordSets.hasResourceRecordSets()) {
                                    boolean duplicateSubdomain = false;
                                    for (ResourceRecordSet recordSet : recordSets.resourceRecordSets()) {
                                        if (RRType.A == recordSet.type()) {
                                            // Hosted Zone alias for the tenant subdomain
                                            String recordSetName = recordSet.name();
                                            String existingSubdomain = recordSetName.substring(0,
                                                    recordSetName.indexOf(domainName) - 1);
                                            if (subdomain.equalsIgnoreCase(existingSubdomain)) {
                                                duplicateSubdomain = true;
                                                break;
                                            }
                                        }
                                    }
                                    if (duplicateSubdomain) {
                                        LOGGER.error("Tenant subdomain " + subdomain
                                                + " is already in use for this hosted zone.");
                                        failOnboarding(onboardingId, "Tenant subdomain " + subdomain
                                                + " is already in use for this hosted zone.");
                                        fatal.add(message);
                                        continue;
                                    }
                                }
                            } catch (Route53Exception route53Error) {
                                LOGGER.error("route53:ListResourceRecordSets error", route53Error);
                                LOGGER.error(Utils.getFullStackTrace(route53Error));
                                failOnboarding(onboardingId, "Can't list Route53 record sets "
                                        + route53Error.awsErrorDetails().errorMessage());
                                fatal.add(message);
                                continue;
                            }
                        }
                    }

                    // Check if Quotas will be exceeded.
                    try {
                        Map<String, Object> retMap = checkLimits(context);
                        Boolean passed = (Boolean) retMap.get("passed");
                        String quotaMessage = (String) retMap.get("message");
                        if (!passed) {
                            LOGGER.error("Provisioning will exceed limits. {}", quotaMessage);
                            failOnboarding(onboardingId, "Provisioning will exceed limits " + quotaMessage);
                            fatal.add(message);
                            continue;
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Error checking Service Quotas with Private API quotas/check", e);
                        LOGGER.warn((Utils.getFullStackTrace(e)));
                        // TODO retry here and see if Quotas comes back online?
                        retry.add(SQSBatchResponse.BatchItemFailure.builder()
                                .withItemIdentifier(messageId)
                                .build()
                        );
                        continue;
                    }

                    // If we made it to the end without continuing on to the next SQS message,
                    // this message is valid
                    LOGGER.info("Onboarding request validated for {}", onboardingId);
                    Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, "saas-boost",
                            OnboardingEvent.ONBOARDING_VALID.detailType(),
                            Map.of("onboardingId", onboarding.getId())
                    );
                }
            }
        }
        if (!fatal.isEmpty()) {
            LOGGER.info("Moving non-recoverable failures to DLQ");
            SendMessageBatchResponse dlq = sqs.sendMessageBatch(request -> request
                    .queueUrl(ONBOARDING_VALIDATION_DLQ)
                    .entries(fatal.stream()
                            .map(msg -> SendMessageBatchRequestEntry.builder()
                                    .id(msg.getMessageId())
                                    .messageBody(msg.getBody())
                                    .build()
                            )
                            .collect(Collectors.toList())
                    )
            );
            LOGGER.info(dlq.toString());
        }
        return SQSBatchResponse.builder().withBatchItemFailures(retry).build();
    }
                //update the Tenant record status
//                try {
//                    ObjectNode systemApiRequest = MAPPER.createObjectNode();
//                    systemApiRequest.put("resource", "tenants/" + tenantId + "/onboarding");
//                    systemApiRequest.put("method", "PUT");
//                    systemApiRequest.put("body", "{\"id\":\"" + tenantId + "\", \"onboardingStatus\":\"" + status + "\"}");
//                    PutEventsRequestEntry systemApiCallEvent = PutEventsRequestEntry.builder()
//                            .eventBusName(SAAS_BOOST_EVENT_BUS)
//                            .detailType(SYSTEM_API_CALL_DETAIL_TYPE)
//                            .source(SYSTEM_API_CALL_SOURCE)
//                            .detail(MAPPER.writeValueAsString(systemApiRequest))
//                            .build();
//                    PutEventsResponse eventBridgeResponse = eventBridge.putEvents(r -> r
//                            .entries(systemApiCallEvent)
//                    );
//                    for (PutEventsResultEntry entry : eventBridgeResponse.entries()) {
//                        if (entry.eventId() != null && !entry.eventId().isEmpty()) {
//                            LOGGER.info("Put event success {} {}", entry.toString(), systemApiCallEvent.toString());
//                        } else {
//                            LOGGER.error("Put event failed {}", entry.toString());
//                        }
//                    }
//
//                    if (status.equals(OnboardingStatus.provisioned)) {
//                        //move the s3 file from the SAAS_BOOST_BUCKET to a key for the tenant and name it config.zip
//                        moveTenantConfigFile(onboarding.getId().toString(), tenantId);
//                    }
//                } catch (JsonProcessingException ioe) {
//                    LOGGER.error("JSON processing failed");
//                    LOGGER.error(Utils.getFullStackTrace(ioe));
//                    throw new RuntimeException(ioe);
//                } catch (SdkServiceException eventBridgeError) {
//                    LOGGER.error("events::PutEvents");
//                    LOGGER.error(Utils.getFullStackTrace(eventBridgeError));
//                    throw eventBridgeError;
//                }

    public Object deleteTenant(Map<String, Object> event, Context context) {
        /*
        Handles a event message to delete a tenant
         */

        //*TODO - Add Lambda function and event rule for "Delete Tenant"
        final long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("OnboardingService::deleteTenant");
        Utils.logRequestEvent(event);
        APIGatewayProxyResponseEvent response = null;
        Map<String, Object> detail = (Map<String, Object>) event.get("detail");
        String tenantId = (String) detail.get("tenantId");
        Onboarding onboarding = dal.getOnboardingByTenantId(tenantId);
        if (onboarding != null) {
            LOGGER.info("OnboardingService::deleteTenant Updating Onboarding status for tenant " + onboarding.getTenantId() + " to DELETING");
            dal.updateStatus(onboarding.getId(), OnboardingStatus.deleting);
        }

        //Now lets delete the CloudFormation stack
        String tenantStackId = "Tenant-" + tenantId.split("-")[0];
        try {
            cfn.deleteStack(DeleteStackRequest.builder().stackName(tenantStackId).build());
        } catch (SdkServiceException cfnError) {
            if (null == cfnError.getMessage() || !cfnError.getMessage().contains("does not exist")) {
                LOGGER.error("deleteCloudFormationStack::deleteStack failed {}", cfnError.getMessage());
                LOGGER.error(Utils.getFullStackTrace(cfnError));
                throw cfnError;
            }
        }

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("OnboardingService::deleteTenant exec " + totalTimeMillis);
        return null;
    }

    private void moveTenantConfigFile(String onboardingId, String tenantId) {
        if (Utils.isBlank(SAAS_BOOST_BUCKET)) {
            throw new IllegalStateException("Missing required environment variable SAAS_BOOST_BUCKET");
        }

        String sourceFile = "temp/" + onboardingId + ".zip";
        LOGGER.info("Start: Move tenant config zip file {} for tenant {}", sourceFile, tenantId);

        //check if S3 file with name onboardingId.zip exists
        String encodedUrl;

        try {
            encodedUrl = URLEncoder.encode(SAAS_BOOST_BUCKET + "/" + sourceFile, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            LOGGER.error("URL could not be encoded: " + e.getMessage());
            throw new RuntimeException("Unable to move tenant zip file " +  sourceFile);
        }

        try {
            ListObjectsRequest listObjects = ListObjectsRequest
                    .builder()
                    .bucket(SAAS_BOOST_BUCKET)
                    .prefix(sourceFile)
                    .build();

            ListObjectsResponse res = s3.listObjects(listObjects);
            if (res.contents().isEmpty()) {
                //no file to copy
                LOGGER.info("No config zip file to copy for tenant {}", tenantId);
                return;
            }

            s3.getObject(GetObjectRequest.builder()
                    .bucket(SAAS_BOOST_BUCKET)
                    .key(sourceFile)
                    .build());
        } catch (S3Exception e) {
            LOGGER.error("Error fetching config zip file {} ", sourceFile + " for tenant " + tenantId);
            LOGGER.error(Utils.getFullStackTrace(e));
            throw new RuntimeException("Unable to copy config zip file " +  sourceFile + " for tenant " + tenantId);
        }
        try {
            s3.copyObject(CopyObjectRequest.builder()
                    .copySource(encodedUrl)
                    .destinationBucket(SAAS_BOOST_BUCKET)
                    .destinationKey("tenants/" + tenantId + "/config.zip")
                    .serverSideEncryption("AES256")
                    .build());
        } catch (S3Exception e) {
            LOGGER.error("Error copying config zip file {} to {}", sourceFile, tenantId + "/config.zip");
            LOGGER.error(Utils.getFullStackTrace(e));
            throw new RuntimeException("Unable to copy config zip file " +  sourceFile + " for tenant " + tenantId);
        }

        //delete the existing file
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(SAAS_BOOST_BUCKET)
                    .key(sourceFile)
                    .build());
        } catch (S3Exception e) {
            LOGGER.error("Error deleting tenant zip file {}", sourceFile);
            LOGGER.error(Utils.getFullStackTrace(e));
            throw new RuntimeException("Unable to delete tenant zip file " +  sourceFile + " for tenant " + tenantId);
        }

        LOGGER.info("Completed: Move tenant config file {} for tenant {}", sourceFile, tenantId);
    }

    public APIGatewayProxyResponseEvent updateProvisionedTenant(Map<String, Object> event, Context context) {
        if (Utils.isBlank(API_GATEWAY_HOST)) {
            throw new IllegalStateException("Missing required environment variable API_GATEWAY_HOST");
        }
        if (Utils.isBlank(API_GATEWAY_STAGE)) {
            throw new IllegalStateException("Missing required environment variable API_GATEWAY_STAGE");
        }
        if (Utils.isBlank(API_TRUST_ROLE)) {
            throw new IllegalStateException("Missing required environment variable API_TRUST_ROLE");
        }
        final long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("OnboardingService::updateProvisionedTenant");
        Utils.logRequestEvent(event);
        APIGatewayProxyResponseEvent response = null;

        // Unlike when we initially provision a tenant and compare the "global" settings
        // to the potentially overriden per-tenant settings, here we're expecting to be
        // told what to set the compute parameters to and assume the proceeding code that
        // called us has save those values globally or per-tenant as appropriate.
        Map<String, Object> tenant = Utils.fromJson((String) event.get("body"), Map.class);
        Onboarding onboarding = dal.getOnboardingByTenantId((String) tenant.get("id"));
        if (onboarding == null) {
            response = new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withHeaders(CORS)
                    .withBody("{\"message\": \"No onboarding record for tenant id " + tenant.get("id") + "\"}");
        } else {
            UUID tenantId = onboarding.getTenantId();
            String stackId = "";//onboarding.getStackId();

            // We have an inconsistency with how the Lambda source folder is managed.
            // If you update an existing SaaS Boost installation with the installer script
            // it will create a new S3 "folder" for the Lambda code packages to force
            // CloudFormation to update the functions. We are now saving this change as
            // part of the global settings, but we'll need to go fetch it here because it's
            // not part of the onboarding request data nor is it part of the tenant data.
            Map<String, Object> settings = fetchSettingsForTenantUpdate(context);
            final String lambdaSourceFolder = (String) settings.get("SAAS_BOOST_LAMBDAS_FOLDER");
            final String templateUrl = "https://" + settings.get("SAAS_BOOST_BUCKET") + ".s3.amazonaws.com/" + settings.get("ONBOARDING_TEMPLATE");

            List<Parameter> templateParameters = new ArrayList<>();
            templateParameters.add(Parameter.builder().parameterKey("TenantId").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("Environment").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("SaaSBoostBucket").usePreviousValue(Boolean.TRUE).build());
            if (Utils.isNotBlank(lambdaSourceFolder)) {
                LOGGER.info("Overriding previous template parameter LambdaSourceFolder to {}", lambdaSourceFolder);
                templateParameters.add(Parameter.builder().parameterKey("LambdaSourceFolder").parameterValue(lambdaSourceFolder).build());
            } else {
                templateParameters.add(Parameter.builder().parameterKey("LambdaSourceFolder").usePreviousValue(Boolean.TRUE).build());
            }
            templateParameters.add(Parameter.builder().parameterKey("DockerHostOS").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("DockerHostInstanceType").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("ContainerRepository").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("ContainerPort").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("ContainerHealthCheckPath").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("CodePipelineRoleArn").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("ArtifactBucket").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("TransitGateway").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("TenantTransitGatewayRouteTable").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("EgressTransitGatewayRouteTable").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("CidrPrefix").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("DomainName").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("SSLCertArnParam").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("HostedZoneId").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("UseEFS").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("MountPoint").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("EncryptEFS").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("EFSLifecyclePolicy").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("UseRDS").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("RDSInstanceClass").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("RDSEngine").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("RDSEngineVersion").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("RDSParameterGroupFamily").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("RDSMasterUsername").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("RDSMasterPasswordParam").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("RDSPort").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("RDSDatabase").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("RDSBootstrap").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("MetricsStream").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("ALBAccessLogsBucket").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("EventBus").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("UseFSx").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("FSxWindowsMountDrive").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("FSxDailyBackupTime").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("FSxBackupRetention").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("FSxThroughputCapacity").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("FSxStorageCapacity").usePreviousValue(Boolean.TRUE).build());
            templateParameters.add(Parameter.builder().parameterKey("FSxWeeklyMaintenanceTime").usePreviousValue(Boolean.TRUE).build());

            Integer taskMemory = (Integer) tenant.get("memory");
            if (taskMemory != null) {
                LOGGER.info("Overriding previous template parameter TaskMemory to {}", taskMemory);
                templateParameters.add(Parameter.builder().parameterKey("TaskMemory").parameterValue(taskMemory.toString()).build());
            } else {
                templateParameters.add(Parameter.builder().parameterKey("TaskMemory").usePreviousValue(Boolean.TRUE).build());
            }

            Integer taskCpu = (Integer) tenant.get("cpu");
            if (taskCpu != null) {
                LOGGER.info("Overriding previous template parameter TaskCPU to {}", taskCpu);
                templateParameters.add(Parameter.builder().parameterKey("TaskCPU").parameterValue(taskCpu.toString()).build());
            } else {
                templateParameters.add(Parameter.builder().parameterKey("TaskCPU").usePreviousValue(Boolean.TRUE).build());
            }

            Integer taskCount = (Integer) tenant.get("min");
            if (taskCount != null) {
                LOGGER.info("Overriding previous template parameter TaskCount to {}", taskCount);
                templateParameters.add(Parameter.builder().parameterKey("TaskCount").parameterValue(taskCount.toString()).build());
            } else {
                templateParameters.add(Parameter.builder().parameterKey("TaskCount").usePreviousValue(Boolean.TRUE).build());
            }

            Integer maxCount = (Integer) tenant.get("max");
            if (maxCount != null) {
                LOGGER.info("Overriding previous template parameter MaxTaskCount to {}", maxCount);
                templateParameters.add(Parameter.builder().parameterKey("MaxTaskCount").parameterValue(maxCount.toString()).build());
            } else {
                templateParameters.add(Parameter.builder().parameterKey("MaxTaskCount").usePreviousValue(Boolean.TRUE).build());
            }

            String billingPlan = (String) tenant.get("planId");
            if (billingPlan != null) {
                LOGGER.info("Overriding previous template parameter BillingPlan to {}", Utils.isBlank(billingPlan) ? "''" : billingPlan);
                templateParameters.add(Parameter.builder().parameterKey("BillingPlan").parameterValue(billingPlan).build());
            } else {
                templateParameters.add(Parameter.builder().parameterKey("BillingPlan").usePreviousValue(Boolean.TRUE).build());
            }

            // Pass in the subdomain each time because a blank value
            // means delete the Route53 record set
            String subdomain = (String) tenant.get("subdomain");
            if (subdomain == null) {
                subdomain = "";
            }
            LOGGER.info("Setting template parameter TenantSubDomain to {}", subdomain);

            templateParameters.add(Parameter.builder().parameterKey("TenantSubDomain").parameterValue(subdomain).build());
            try {
                UpdateStackResponse cfnResponse = cfn.updateStack(UpdateStackRequest.builder()
                        .stackName(stackId)
                        .usePreviousTemplate(Boolean.FALSE)
                        .templateURL(templateUrl)
                        .capabilitiesWithStrings("CAPABILITY_NAMED_IAM", "CAPABILITY_AUTO_EXPAND")
                        .parameters(templateParameters)
                        .build()
                );
                stackId = cfnResponse.stackId();
                dal.updateStatus(onboarding.getId(), OnboardingStatus.updating);
                LOGGER.info("OnboardingService::updateProvisionedTenant stack id " + stackId);
            } catch (SdkServiceException cfnError) {
                // CloudFormation throws a 400 error if it doesn't detect any resources in a stack
                // need to be updated. Swallow this error.
                if (cfnError.getMessage().contains("No updates are to be performed")) {
                    LOGGER.warn("cloudformation::updateStack error {}", cfnError.getMessage());
                } else {
                    LOGGER.error("cloudformation::updateStack failed {}", cfnError.getMessage());
                    LOGGER.error(Utils.getFullStackTrace(cfnError));
                    dal.updateStatus(onboarding.getId(), OnboardingStatus.failed);
                    throw cfnError;
                }
            }

            response = new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(CORS)
                    .withBody("{\"stackId\": \"" + stackId + "\"}");
        }
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("OnboardingService::updateProvisionedTenant exec " + totalTimeMillis);
        return response;
    }

    public APIGatewayProxyResponseEvent resetDomainName(Map<String, Object> event, Context context) {
        if (Utils.isBlank(API_GATEWAY_HOST)) {
            throw new IllegalStateException("Missing required environment variable API_GATEWAY_HOST");
        }
        if (Utils.isBlank(API_GATEWAY_STAGE)) {
            throw new IllegalStateException("Missing required environment variable API_GATEWAY_STAGE");
        }
        if (Utils.isBlank(API_TRUST_ROLE)) {
            throw new IllegalStateException("Missing required environment variable API_TRUST_ROLE");
        }
        final long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("OnboardingService::resetDomainName");
        Utils.logRequestEvent(event);

        Map<String, String> settings;
        ApiRequest getSettingsRequest = ApiRequest.builder()
                .resource("settings?setting=SAAS_BOOST_STACK&setting=DOMAIN_NAME")
                .method("GET")
                .build();
        SdkHttpFullRequest getSettingsApiRequest = ApiGatewayHelper.getApiRequest(API_GATEWAY_HOST, API_GATEWAY_STAGE, getSettingsRequest);
        LOGGER.info("Fetching SaaS Boost stack and domain name from Settings Service");
        try {
            String getSettingsResponseBody = ApiGatewayHelper.signAndExecuteApiRequest(getSettingsApiRequest, API_TRUST_ROLE, context.getAwsRequestId());
            ArrayList<Map<String, String>> getSettingsResponse = Utils.fromJson(getSettingsResponseBody, ArrayList.class);
            settings = getSettingsResponse
                    .stream()
                    .collect(Collectors.toMap(
                            setting -> setting.get("name"), setting -> setting.get("value")
                    ));
        } catch (Exception e) {
            LOGGER.error("Error invoking API settings");
            LOGGER.error(Utils.getFullStackTrace(e));
            throw new RuntimeException(e);
        }

        LOGGER.info("Calling cloudFormation update-stack --stack-name {}", settings.get("SAAS_BOOST_STACK"));
        String stackId = null;
        try {
            UpdateStackResponse cfnResponse = cfn.updateStack(UpdateStackRequest.builder()
                    .stackName(settings.get("SAAS_BOOST_STACK"))
                    .usePreviousTemplate(Boolean.TRUE)
                    .capabilitiesWithStrings("CAPABILITY_NAMED_IAM", "CAPABILITY_AUTO_EXPAND")
                    .parameters(
                            Parameter.builder().parameterKey("DomainName").parameterValue(settings.get("DOMAIN_NAME")).build(),
                            Parameter.builder().parameterKey("SaaSBoostBucket").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("LambdaSourceFolder").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("Environment").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("AdminEmailAddress").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("PublicApiStage").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("PrivateApiStage").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("Version").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("ADPasswordParam").usePreviousValue(Boolean.TRUE).build()
                    )
                    .build()
            );
            stackId = cfnResponse.stackId();
            LOGGER.info("OnboardingService::resetDomainName stack id " + stackId);
        } catch (SdkServiceException cfnError) {
            // CloudFormation throws a 400 error if it doesn't detect any resources in a stack
            // need to be updated. Swallow this error.
            if (cfnError.getMessage().contains("No updates are to be performed")) {
                LOGGER.warn("cloudformation::updateStack error {}", cfnError.getMessage());
            } else {
                LOGGER.error("cloudformation::updateStack failed {}", cfnError.getMessage());
                LOGGER.error(Utils.getFullStackTrace(cfnError));
                throw cfnError;
            }
        }

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(CORS)
                .withBody("{\"stackId\": \"" + stackId + "\"}");

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("OnboardingService::resetDomainName exec " + totalTimeMillis);
        return response;
    }

    public APIGatewayProxyResponseEvent updateAppConfig(Map<String, Object> event, Context context) {
        if (Utils.isBlank(API_GATEWAY_HOST)) {
            throw new IllegalStateException("Missing required environment variable API_GATEWAY_HOST");
        }
        if (Utils.isBlank(API_GATEWAY_STAGE)) {
            throw new IllegalStateException("Missing required environment variable API_GATEWAY_STAGE");
        }
        if (Utils.isBlank(API_TRUST_ROLE)) {
            throw new IllegalStateException("Missing required environment variable API_TRUST_ROLE");
        }
        final long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("OnboardingService::updateAppConfig");
        Utils.logRequestEvent(event);

        String stackName;
        ApiRequest getSettingsRequest = ApiRequest.builder()
                .resource("settings/SAAS_BOOST_STACK/secret")
                .method("GET")
                .build();
        SdkHttpFullRequest getSettingsApiRequest = ApiGatewayHelper.getApiRequest(API_GATEWAY_HOST, API_GATEWAY_STAGE, getSettingsRequest);
        LOGGER.info("Fetching SaaS Boost stack name from Settings Service");
        try {
            String getSettingsResponseBody = ApiGatewayHelper.signAndExecuteApiRequest(getSettingsApiRequest, API_TRUST_ROLE, context.getAwsRequestId());
            Map<String, String> getSettingsResponse = Utils.fromJson(getSettingsResponseBody, LinkedHashMap.class);
            stackName = getSettingsResponse.get("value");
        } catch (Exception e) {
            LOGGER.error("Error invoking API settings");
            LOGGER.error(Utils.getFullStackTrace(e));
            throw new RuntimeException(e);
        }

        Map<String, Object> appConfig = getAppConfig(context);
        Map<String, Object> services = (Map<String, Object>) appConfig.get("services");

        LOGGER.info("Calling cloudFormation update-stack --stack-name {}", stackName);
        String stackId = null;
        try {
            UpdateStackResponse cfnResponse = cfn.updateStack(UpdateStackRequest.builder()
                    .stackName(stackName)
                    .usePreviousTemplate(Boolean.TRUE)
                    .capabilitiesWithStrings("CAPABILITY_NAMED_IAM", "CAPABILITY_AUTO_EXPAND")
                    .parameters(
                            Parameter.builder().parameterKey("SaaSBoostBucket").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("LambdaSourceFolder").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("Environment").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("AdminEmailAddress").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("DomainName").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("SSLCertificate").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("PublicApiStage").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("PrivateApiStage").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("Version").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("DeployActiveDirectory").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("ADPasswordParam").usePreviousValue(Boolean.TRUE).build(),
                            Parameter.builder().parameterKey("ApplicationServices").parameterValue(String.join(",", services.keySet())).build()
                    )
                    .build()
            );
            stackId = cfnResponse.stackId();
            LOGGER.info("OnboardingService::updateAppConfig stack id " + stackId);
        } catch (SdkServiceException cfnError) {
            // CloudFormation throws a 400 error if it doesn't detect any resources in a stack
            // need to be updated. Swallow this error.
            if (cfnError.getMessage().contains("No updates are to be performed")) {
                LOGGER.warn("cloudformation::updateStack error {}", cfnError.getMessage());
            } else {
                LOGGER.error("cloudformation::updateStack failed {}", cfnError.getMessage());
                LOGGER.error(Utils.getFullStackTrace(cfnError));
                throw cfnError;
            }
        }

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(CORS)
                .withBody("{\"stackId\": \"" + stackId + "\"}");

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("OnboardingService::updateAppConfig exec " + totalTimeMillis);
        return response;
    }

    public void handleTenantEvent(Map<String, Object> event, Context context) {

    }

    public void handleAppConfigEvent(Map<String, Object> event, Context context) {

    }

    protected void failOnboarding(String onboardingId, String message) {
        failOnboarding(UUID.fromString(onboardingId), message);
    }

    protected void failOnboarding(UUID onboardingId, String message) {
        dal.updateStatus(onboardingId, OnboardingStatus.failed);
        Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, "saas-boost",
                OnboardingEvent.ONBOARDING_FAILED.detailType(), Map.of("onboardingId", onboardingId,
                        "message", message));
    }

    protected Map<String, Object> fetchSettingsForTenantUpdate(Context context) {
        Map<String, Object> settings;
        try {
            String getSettingResponseBody = ApiGatewayHelper.signAndExecuteApiRequest(
                    ApiGatewayHelper.getApiRequest(
                            API_GATEWAY_HOST,
                            API_GATEWAY_STAGE,
                            ApiRequest.builder()
                                    .resource("settings?setting=SAAS_BOOST_BUCKET&setting=SAAS_BOOST_LAMBDAS_FOLDER&setting=ONBOARDING_TEMPLATE")
                                    .method("GET")
                                    .build()
                    ),
                    API_TRUST_ROLE,
                    context.getAwsRequestId()
            );
            List<Map<String, Object>> settingsResponse = Utils.fromJson(getSettingResponseBody, ArrayList.class);
            settings = settingsResponse
                    .stream()
                    .collect(Collectors.toMap(
                            setting -> (String) setting.get("name"), setting -> setting.get("value")
                    ));
        } catch (Exception e) {
            LOGGER.error("Error invoking API " + API_GATEWAY_STAGE + "/settings?setting=SAAS_BOOST_BUCKET&setting=SAAS_BOOST_LAMBDAS_FOLDER&setting=ONBOARDING_TEMPLATE");
            LOGGER.error(Utils.getFullStackTrace(e));
            throw new RuntimeException(e);
        }
        return settings;
    }

    /*
    Check deployed services against service quotas to make sure limits will not be exceeded.
     */
    protected Map<String, Object> checkLimits(Context context) throws Exception {
        if (Utils.isBlank(API_GATEWAY_HOST)) {
            throw new IllegalStateException("Missing environment variable API_GATEWAY_HOST");
        }
        if (Utils.isBlank(API_GATEWAY_STAGE)) {
            throw new IllegalStateException("Missing environment variable API_GATEWAY_STAGE");
        }
        if (Utils.isBlank(API_TRUST_ROLE)) {
            throw new IllegalStateException("Missing environment variable API_TRUST_ROLE");
        }
        long startMillis = System.currentTimeMillis();
        Map<String, Object> valMap;
        ApiRequest tenantsRequest = ApiRequest.builder()
                .resource("quotas/check")
                .method("GET")
                .build();
        SdkHttpFullRequest apiRequest = ApiGatewayHelper.getApiRequest(API_GATEWAY_HOST, API_GATEWAY_STAGE, tenantsRequest);
        String responseBody;
        try {
            LOGGER.info("API call for quotas/check");
            responseBody = ApiGatewayHelper.signAndExecuteApiRequest(apiRequest, API_TRUST_ROLE, context.getAwsRequestId());
//            LOGGER.info("API response for quoatas/check: " + responseBody);
            valMap = Utils.fromJson(responseBody, HashMap.class);
        } catch (Exception e) {
            LOGGER.error("Error invoking API quotas/check");
            LOGGER.error(Utils.getFullStackTrace(e));
            throw new RuntimeException(e);
        }

        LOGGER.debug("checkLimits: Total time to check service limits: " + (System.currentTimeMillis() - startMillis));
        return valMap;
    }

    protected Map<String, Object> getAppConfig(Context context) {
        // Fetch all of the services configured for this application
        LOGGER.info("Calling settings service get app config API");
        String getAppConfigResponseBody = ApiGatewayHelper.signAndExecuteApiRequest(
                ApiGatewayHelper.getApiRequest(
                        API_GATEWAY_HOST,
                        API_GATEWAY_STAGE,
                        ApiRequest.builder()
                                .resource("settings/config")
                                .method("GET")
                                .build()
                ),
                API_TRUST_ROLE,
                context.getAwsRequestId()
        );
        Map<String, Object> appConfig = Utils.fromJson(getAppConfigResponseBody, LinkedHashMap.class);
        return appConfig;
    }

    protected Map<String, Object> getTenant(UUID tenantId, Context context) {
        if (tenantId == null) {
            throw new IllegalArgumentException("Can't fetch blank tenant id");
        }
        return getTenant(tenantId.toString(), context);
    }

    protected Map<String, Object> getTenant(String tenantId, Context context) {
        if (Utils.isBlank(tenantId)) {
            throw new IllegalArgumentException("Can't fetch blank tenant id");
        }
        // Fetch all of the services configured for this application
        LOGGER.info("Calling tenant service get tenant {}", tenantId);
        String getTenantResponseBody = ApiGatewayHelper.signAndExecuteApiRequest(
                ApiGatewayHelper.getApiRequest(
                        API_GATEWAY_HOST,
                        API_GATEWAY_STAGE,
                        ApiRequest.builder()
                                .resource("tenants/" + tenantId)
                                .method("GET")
                                .build()
                ),
                API_TRUST_ROLE,
                context.getAwsRequestId()
        );
        Map<String, Object> tenant = Utils.fromJson(getTenantResponseBody, LinkedHashMap.class);
        return tenant;
    }

    protected Map<String, Integer> getPathPriority(Map<String, Object> appConfig) {
        Map<String, Object> services = (Map<String, Object>) appConfig.get("services");
        Map<String, Integer> pathLength = new HashMap<>();

        // Collect the string length of the path for each public service
        for (Map.Entry<String, Object> serviceConfig : services.entrySet()) {
            String serviceName = serviceConfig.getKey();
            Map<String, Object> service = (Map<String, Object>) serviceConfig.getValue();
            Boolean isPublic = (Boolean) service.get("public");
            if (isPublic) {
                String pathPart = Objects.toString(service.get("path"), "");
                pathLength.put(serviceName, pathPart.length());
            }
        }
        // Order the services by longest (most specific) to shortest (least specific) path length
        LinkedHashMap<String, Integer> pathPriority = pathLength.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Collections.reverseOrder()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (value1, value2) -> value1, LinkedHashMap::new
                ));
        // Set the ALB listener rule priority so that they most specific paths (the longest ones) have
        // a higher priority than the less specific paths so the rules are evaluated in the proper order
        // i.e. a path of /feature* needs to be evaluate before a catch all path of /* or you'll never
        // route to the /feature* rule because /* will have already matched
        int priority = 0;
        for (String publicService : pathPriority.keySet()) {
            pathPriority.put(publicService, ++priority);
        }
        return pathPriority;
    }
}