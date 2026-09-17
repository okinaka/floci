package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.Contact;
import io.github.hectorvent.floci.services.ses.model.CustomVerificationEmailTemplate;
import io.github.hectorvent.floci.services.ses.model.DeliveryOptions;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.TrackingOptions;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import io.github.hectorvent.floci.services.ses.model.Tenant;
import io.github.hectorvent.floci.services.ses.model.TenantResourceAssociation;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import io.github.hectorvent.floci.services.ses.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * What is left of the original SES facade once every domain moved into its own service and the send
 * path stayed in {@link SesService}: the flows that touch two or more domains and so belong to
 * none of them. Deletes guarded by tenant associations and cascading into policies,
 * configuration-set options validated against another domain, the tenant to resource associations,
 * the ARN dispatch behind the tag operations, the tenant-scoped suppression routing, and the
 * inspection reads. It owns no store, and it is meant to shrink as each remaining flow finds a
 * home.
 */
@ApplicationScoped
public class SesCrossDomainService {

    private static final Logger LOG = Logger.getLogger(SesCrossDomainService.class);

    // Identities live in SesIdentityService (CRUD, verification, MAIL FROM, notifications, tags,
    // and the DKIM state machine with its Route53 lookup), which the v2 controller and the v1
    // handler call directly. What stays here is cross-domain: create's configuration-set check, the
    // tenant-guarded delete with its policy cascade, the default configuration set, the
    // verified-domain probe behind tracking options, and the tagging, reaching the store through
    // its find/save.
    private final SesIdentityService identityService;
    // Sent-email records extracted to SesSentEmailService. The account and v1 statistics reads go
    // to the service directly; only the inspection endpoints still read and clear through here.
    private final SesSentEmailService sentEmailService;
    // Email templates extracted to SesTemplateService, which the v2 controller and the v1 handler
    // call directly; the tenant-guarded delete and the ARN-dispatched tagging stay here.
    private final SesTemplateService templateService;
    // Configuration sets live in SesConfigurationSetService, which the v2 controller and the v1
    // handler call directly; what stays here is the cross-domain option validation (tracking's
    // verified domain, delivery's dedicated pool), the tenant-guarded delete, and the
    // ARN-dispatched tagging.
    private final SesConfigurationSetService configSetService;
    // Account suppression attributes + the per-address suppression list (two stores) extracted to
    // SesSuppressionService. What stays here is the TenantName routing, which picks the
    // tenant-scoped store or the account-wide one, and the cascade dropping a deleted tenant's
    // entries.
    private final SesSuppressionService suppressionService;
    // Dedicated IP pools and IPs live in SesDedicatedIpService, which the v2 controller calls
    // directly; this class only reaches it for the delivery-options pool probe and the
    // ARN-dispatched tagging.
    private final SesDedicatedIpService dedicatedIpService;
    // Contact lists and contacts (two stores) live in SesContactService, which the v2 controller
    // and the unsubscribe endpoint call directly; this class only reaches it for the
    // ARN-dispatched tagging.
    private final SesContactService contactService;
    // Identity (sending authorization) policy storage lives in SesPolicyService, which the v1
    // handler calls directly; the v2 operations, which check the identity exists first, and the
    // identity delete cascade stay here.
    private final SesPolicyService policyService;
    // Custom verification email templates: storage extracted to SesCvetService, which the v2
    // controller and the v1 handler call directly for get/list/delete. Create and update stay here
    // for the identity-dependent validation, as does the tag dispatch.
    private final SesCvetService cvetService;
    // Tenants (multi-tenancy) live in SesTenantService, which the v2 controller calls directly for
    // the tenant record; the associations, the delete cascade and the tenant-scoped suppression
    // routing stay here.
    private final SesTenantService tenantService;
    private final String defaultAccountId;
    // Resolves the caller's account per request so a tag operation can reject an ARN carrying
    // another account. Null in the package-private test constructor (falls back to
    // defaultAccountId).
    private final RegionResolver regionResolver;

    @Inject
    public SesCrossDomainService(SesIdentityService identityService, SesCvetService cvetService,
                                 SesPolicyService policyService, SesContactService contactService,
                                 SesSuppressionService suppressionService,
                                 SesDedicatedIpService dedicatedIpService,
                                 SesTemplateService templateService,
                                 SesSentEmailService sentEmailService,
                                 SesTenantService tenantService,
                                 SesConfigurationSetService configSetService,
                                 EmulatorConfig config, RegionResolver regionResolver) {
        this.identityService = identityService;
        this.sentEmailService = sentEmailService;
        this.templateService = templateService;
        this.configSetService = configSetService;
        this.suppressionService = suppressionService;
        this.dedicatedIpService = dedicatedIpService;
        this.contactService = contactService;
        this.policyService = policyService;
        this.cvetService = cvetService;
        this.tenantService = tenantService;
        this.defaultAccountId = config.defaultAccountId();
        this.regionResolver = regionResolver;
    }

    SesCrossDomainService(SesIdentityService identityService,
                          SesSentEmailService sentEmailService,
                          SesTemplateService templateService,
                          SesConfigurationSetService configSetService,
                          SesSuppressionService suppressionService,
                          SesDedicatedIpService dedicatedIpService,
                          SesContactService contactService,
                          SesPolicyService policyService,
                          SesCvetService cvetService,
                          SesTenantService tenantService) {
        this.identityService = identityService;
        this.sentEmailService = sentEmailService;
        this.templateService = templateService;
        this.configSetService = configSetService;
        this.suppressionService = suppressionService;
        this.dedicatedIpService = dedicatedIpService;
        this.contactService = contactService;
        this.policyService = policyService;
        this.cvetService = cvetService;
        this.tenantService = tenantService;
        this.defaultAccountId = "000000000000";
        this.regionResolver = null;
    }

    /**
     * v2 CreateEmailIdentity. The identity domain builds and persists the complete record in one
     * write; only the configuration-set existence check is cross-domain, so it is passed in as the
     * in-lock callback (a missing set fails the whole call and nothing is created, matching AWS).
     */
    public Identity createEmailIdentity(String emailIdentity, String configurationSetName,
                                        List<Tag> tags, String region) {
        Runnable configurationSetExistsCheck = configurationSetName == null ? null
                : () -> configSetService.get(configurationSetName, region);
        return identityService.createEmailIdentity(emailIdentity, configurationSetName, tags, region,
                configurationSetExistsCheck);
    }

    public void deleteIdentity(String identityValue, String region) {
        if (identityValue == null || identityValue.isBlank()) {
            return;
        }
        tenantService.deleteBackingResource(SesTenantService.RESOURCE_TYPE_IDENTITY, identityValue,
                region, () -> doDeleteIdentity(identityValue, region));
    }

    private void doDeleteIdentity(String identityValue, String region) {
        identityService.delete(identityValue, region);

        // Policies are sub-resources of the identity; drop them too so they can't resurrect into a
        // same-named identity recreated later (and so the per-identity count stays correct).
        policyService.deletePoliciesForIdentity(identityValue, region);

        LOG.infov("Deleted identity: {0}", identityValue);
    }

    public void setEmailIdentityConfigurationSet(String identityValue, String configurationSetName,
                                                 String region) {
        Identity identity = identityService.find(identityValue, region)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Identity <" + identityValue + "> does not exist.", 404));
        boolean clearing = configurationSetName == null || configurationSetName.isEmpty();
        if (!clearing) {
            configSetService.get(configurationSetName, region);
        }
        identity.setConfigurationSetName(clearing ? null : configurationSetName);
        identityService.save(identity, region);
        LOG.infov("Updated default ConfigurationSet for {0}: {1}",
                identityValue, clearing ? "<cleared>" : configurationSetName);
    }

    public List<SentEmail> getEmails() {
        return sentEmailService.listAll();
    }

    public void clearEmails() {
        sentEmailService.clear();
    }

    // ──────────────────────────── Templates ────────────────────────────

    // Email templates live in SesTemplateService, which the v2 controller and the v1 handler call
    // directly; only the delete stays here for the tenant-association guard. The templated-send
    // path below reads templates through the service, and ARN-dispatched tagging through find/save.

    public void deleteTemplate(String templateName, String region) {
        tenantService.deleteBackingResource(SesTenantService.RESOURCE_TYPE_TEMPLATE, templateName,
                region, () -> templateService.deleteTemplate(templateName, region));
    }

    // ──────────── Custom verification email templates (v1 + v2 shared store) ────────────
    // Verified against real AWS: the From address must be a verified identity, redirection URLs
    // must be valid, and the template body is not content-validated. Floci enforces the
    // From-verified check against its own identity store (it does track verified identities).

    public void createCustomVerificationEmailTemplate(CustomVerificationEmailTemplate template, String region) {
        // The From-verified check inside validation reaches the Identity domain, so the facade
        // validates here before the storage service performs the create.
        validateCustomVerificationTemplate(template, region);
        cvetService.createCustomVerificationEmailTemplate(template, region);
    }

    public void updateCustomVerificationEmailTemplate(CustomVerificationEmailTemplate template, String region) {
        // Validate (including the From-verified identity check and the required-field checks) before
        // delegating the storage update, matching createCustomVerificationEmailTemplate.
        validateCustomVerificationTemplate(template, region);
        cvetService.updateCustomVerificationEmailTemplate(template, region);
    }

    private void validateCustomVerificationTemplate(CustomVerificationEmailTemplate t, String region) {
        requireCvetField(t.getTemplateName(), "TemplateName");
        requireCvetField(t.getFromEmailAddress(), "FromEmailAddress");
        requireCvetField(t.getTemplateSubject(), "TemplateSubject");
        requireCvetField(t.getTemplateContent(), "TemplateContent");
        requireCvetField(t.getSuccessRedirectionURL(), "SuccessRedirectionURL");
        requireCvetField(t.getFailureRedirectionURL(), "FailureRedirectionURL");
        if (!identityService.isVerifiedSender(t.getFromEmailAddress(), region)) {
            // v1-native code (verified: FromEmailAddressNotVerified / 400); remapV1Exception
            // translates it to NotFoundException / 404 for the v2 boundary.
            throw new AwsException("FromEmailAddressNotVerified",
                    "The from email address <" + t.getFromEmailAddress() + "> is not verified", 400);
        }
        if (!isValidRedirectUrl(t.getSuccessRedirectionURL())) {
            throw new AwsException("InvalidParameterValue", "The success redirection URL is invalid", 400);
        }
        if (!isValidRedirectUrl(t.getFailureRedirectionURL())) {
            throw new AwsException("InvalidParameterValue", "The failure redirection URL is invalid", 400);
        }
    }

    private static boolean isValidRedirectUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void requireCvetField(String value, String name) {
        if (value == null || value.isBlank()) {
            // v1-native code; the v2 controller remaps it to BadRequestException via remapV1Exception,
            // so the Query API stays consistent with requireParam and v2 behavior is unchanged.
            throw new AwsException("InvalidParameterValue", name + " is required.", 400);
        }
    }

    public ConfigurationSet createConfigurationSet(ConfigurationSet configSet, String region) {
        return configSetService.createConfigurationSet(configSet, region,
                domain -> isVerifiedDomainIdentity(domain, region),
                pool -> dedicatedIpService.dedicatedIpPoolExists(pool, region));
    }

    // The option operations live in the service; the facade only supplies the cross-domain probes
    // (a verified domain identity, a dedicated IP pool) as predicates.

    public void setConfigurationSetTrackingOptions(String configSetName, TrackingOptions options, String region) {
        configSetService.setTrackingOptions(configSetName, options, region,
                domain -> isVerifiedDomainIdentity(domain, region));
    }

    public void setConfigurationSetDeliveryOptions(String configSetName, DeliveryOptions options, String region) {
        configSetService.setDeliveryOptions(configSetName, options, region,
                pool -> dedicatedIpService.dedicatedIpPoolExists(pool, region));
    }

    private boolean isVerifiedDomainIdentity(String domain, String region) {
        Identity identity = identityService.getIdentityVerificationAttributes(domain, region);
        return identity != null && "Success".equals(identity.getVerificationStatus())
                && "Domain".equals(identity.getIdentityType());
    }

    public void createConfigurationSetTrackingOptions(String configSetName, String customRedirectDomain,
                                                      String region) {
        configSetService.createTrackingOptions(configSetName, customRedirectDomain, region,
                domain -> isVerifiedDomainIdentity(domain, region));
    }

    public void updateConfigurationSetTrackingOptions(String configSetName, String customRedirectDomain,
                                                      String region) {
        configSetService.updateTrackingOptions(configSetName, customRedirectDomain, region,
                domain -> isVerifiedDomainIdentity(domain, region));
    }

    public void deleteConfigurationSet(String name, String region) {
        configSetService.get(name, region);
        tenantService.deleteBackingResource(SesTenantService.RESOURCE_TYPE_CONFIGURATION_SET, name,
                region, () -> configSetService.remove(name, region));
    }

    // ──────────────────────── Tenants (multi-tenancy) ────────────────────────
    // Tenants live in SesTenantService, which the v2 controller calls directly for the tenant
    // record and its suppression attributes; the facade keeps the resource associations (they
    // check the identity, configuration set or template exists) and the delete cascade.

    public void deleteTenant(String tenantName, String region) {
        // The tenant-scoped suppression entries live in the suppression domain; the callback runs
        // their cascade inside the tenant lock, before the associations and the tenant record.
        tenantService.deleteTenant(tenantName, region,
                tenant -> suppressionService.deleteAllForTenant(region, tenant.tenantId()));
    }

    // The association operations validate resource existence here, not in the tenant domain: that
    // owns the association store, but only this class can reach the identity/configuration-set/template
    // stores without a service→service dependency.

    public void createTenantResourceAssociation(String tenantName, String resourceArn,
                                                String accountId, String region) {
        SesTenantService.AssociationResource ref =
                SesTenantService.parseResourceArn(resourceArn, accountId, region);
        Tenant tenant = tenantService.tenantForAssociation(tenantName, region);
        // The existence check runs inside the association lock so it stays atomic with the
        // backing-resource delete guards.
        tenantService.associate(tenant, ref, region, () -> requireTenantResourceExists(ref, region));
    }

    public void deleteTenantResourceAssociation(String tenantName, String resourceArn,
                                                String accountId, String region) {
        SesTenantService.AssociationResource ref =
                SesTenantService.parseResourceArn(resourceArn, accountId, region);
        Tenant tenant = tenantService.tenantForAssociation(tenantName, region);
        // AWS still 404s on a missing resource even though removing a missing association succeeds.
        requireTenantResourceExists(ref, region);
        tenantService.disassociate(tenant, ref, region);
    }

    public List<TenantResourceAssociation> listTenantResources(String tenantName,
                                                               String resourceTypeFilter,
                                                               Integer pageSize, String nextToken,
                                                               String region) {
        SesTenantService.validateListPaging(pageSize, nextToken);
        SesTenantService.validateResourceTypeFilter(resourceTypeFilter);
        Tenant tenant = tenantService.tenantForAssociation(tenantName, region);
        return tenantService.listTenantResources(tenant, resourceTypeFilter, region);
    }

    public List<TenantResourceAssociation> listResourceTenants(String resourceArn, Integer pageSize,
                                                               String nextToken, String accountId,
                                                               String region) {
        SesTenantService.validateListPaging(pageSize, nextToken);
        SesTenantService.AssociationResource ref =
                SesTenantService.parseResourceArn(resourceArn, accountId, region);
        requireTenantResourceExists(ref, region);
        return tenantService.listResourceTenants(ref, region);
    }

    // The association APIs 404 with a per-type message when the referenced resource is missing; the
    // trailing colon on the configuration-set variant is AWS's own.
    private void requireTenantResourceExists(SesTenantService.AssociationResource ref, String region) {
        boolean exists = switch (ref.type()) {
            case SesTenantService.RESOURCE_TYPE_IDENTITY ->
                    identityService.find(ref.name(), region).isPresent();
            case SesTenantService.RESOURCE_TYPE_CONFIGURATION_SET ->
                    SesConfigurationSetService.isValidName(ref.name())
                            && configSetService.find(ref.name(), region).isPresent();
            case SesTenantService.RESOURCE_TYPE_TEMPLATE ->
                    templateService.find(ref.name(), region).isPresent();
            default -> false;
        };
        if (exists) {
            return;
        }
        String message = switch (ref.type()) {
            case SesTenantService.RESOURCE_TYPE_IDENTITY ->
                    "Identity <" + ref.name() + "> does not exist";
            case SesTenantService.RESOURCE_TYPE_CONFIGURATION_SET ->
                    "Configuration set <" + ref.name() + "> does not exist:";
            default -> "Email template <" + ref.name() + "> does not exist";
        };
        throw new AwsException("NotFoundException", message, 404);
    }

    // ──────────────── Identity (sending authorization) policies ────────────────
    // One shared store behind the v1 (PutIdentityPolicy/GetIdentityPolicies/ListIdentityPolicies/
    // DeleteIdentityPolicy) and v2 (Create/Get/Update/DeleteEmailIdentityPolicy) APIs. Verified
    // against real AWS. Floci stores and returns policies but does not enforce the authorization
    // (Principal-account existence, Resource-ARN match, or send-time checks): it has no account
    // registry and does not gate sending, so these are treated as metadata.
    // Policy storage lives in SesPolicyService; this facade forwards, and for the v2 mutators it runs
    // the identity-existence check (an Identity-domain read) first, before delegating.

    public void createEmailIdentityPolicy(String identity, String policyName, String policy, String region) {
        requireIdentityExists(identity, region);
        policyService.createEmailIdentityPolicy(identity, policyName, policy, region);
    }

    public void updateEmailIdentityPolicy(String identity, String policyName, String policy, String region) {
        requireIdentityExists(identity, region);
        policyService.updateEmailIdentityPolicy(identity, policyName, policy, region);
    }

    public Map<String, String> getEmailIdentityPolicies(String identity, String region) {
        requireIdentityExists(identity, region);
        return policyService.listAllPolicies(identity, region);
    }

    public void deleteEmailIdentityPolicy(String identity, String policyName, String region) {
        requireIdentityExists(identity, region);
        policyService.deleteEmailIdentityPolicy(identity, policyName, region);
    }

    private void requireIdentityExists(String identity, String region) {
        if (identityService.find(identity, region).isEmpty()) {
            throw new AwsException("NotFoundException",
                    "Email identity <" + identity + "> does not exist.", 404);
        }
    }


    public List<Tag> listResourceTags(String arn, String region) {
        ResourceRef ref = parseSesArn(arn);
        requireCallerAccount(ref);
        List<Tag> tags = switch (ref.type()) {
            case "configuration-set" -> configSetService.listTags(ref.name(), region);
            case "template" -> templateService.listTags(ref.name(), region);
            case "identity" -> identityService.listTags(ref.name(), region);
            case "contact-list" -> contactService.listTags(ref.name(), region);
            case "custom-verification-email-template" -> cvetService.listTags(ref.name(), region);
            case "dedicated-ip-pool" -> dedicatedIpService.listTags(ref.name(), region);
            case "tenant" -> tenantService.listTags(ref.name(), region);
            default -> throw new AwsException("NotFoundException",
                    "Resource " + arn + " was not found.", 404);
        };
        // AWS checks existence against the signing region but keys the tag store by the literal
        // ARN: a mismatched ARN region passes the existence check above yet addresses an ARN
        // nothing was ever tagged under, so the result is empty (probe-confirmed across all six
        // resource types).
        if (!ref.region().equals(region)) {
            return List.of();
        }
        return tags;
    }

    public void tagResource(String arn, String region, List<Tag> newTags) {
        ResourceRef ref = parseSesArn(arn);
        requireCallerAccount(ref);
        if (!ref.region().equals(region)) {
            throw new AwsException("BadRequestException", "Failed to tag resource", 400);
        }
        // An empty Tags list is not an error: AWS still runs the account, region, and existence
        // checks and then applies the empty merge as a no-op (probe-confirmed).
        List<Tag> tags = newTags == null ? List.of() : newTags;
        SesTags.validate(tags);
        switch (ref.type()) {
            case "configuration-set" -> configSetService.tag(ref.name(), region, tags);
            case "template" -> templateService.tag(ref.name(), region, tags);
            case "identity" -> identityService.tag(ref.name(), region, tags);
            case "contact-list" -> contactService.tag(ref.name(), region, tags);
            case "custom-verification-email-template" -> cvetService.tag(ref.name(), region, tags);
            case "dedicated-ip-pool" -> dedicatedIpService.tag(ref.name(), region, tags);
            case "tenant" -> tenantService.tag(ref.name(), region, tags);
            default -> throw new AwsException("NotFoundException",
                    "Resource " + arn + " was not found.", 404);
        }
    }

    public void untagResource(String arn, String region, List<String> tagKeys) {
        ResourceRef ref = parseSesArn(arn);
        requireCallerAccount(ref);
        if (tagKeys == null || tagKeys.isEmpty()) {
            // AWS rejects a missing/empty TagKeys member with a message-less ValidationException
            // (probe-confirmed: only the error-type header, empty body), after the account guard
            // and before the region guard. The null message is deliberate: it surfaces through
            // Floci's standard error body as "message":null, which restJson1 SDKs parse the same
            // way as AWS's empty body since they read x-amzn-errortype first.
            throw new AwsException("ValidationException", null, 400);
        }
        if (!ref.region().equals(region)) {
            throw new AwsException("BadRequestException", "Failed to untag resource", 400);
        }
        switch (ref.type()) {
            case "configuration-set" -> configSetService.untag(ref.name(), region, tagKeys);
            case "template" -> templateService.untag(ref.name(), region, tagKeys);
            case "identity" -> identityService.untag(ref.name(), region, tagKeys);
            case "contact-list" -> contactService.untag(ref.name(), region, tagKeys);
            case "custom-verification-email-template" -> cvetService.untag(ref.name(), region, tagKeys);
            case "dedicated-ip-pool" -> dedicatedIpService.untag(ref.name(), region, tagKeys);
            case "tenant" -> tenantService.untag(ref.name(), region, tagKeys);
            default -> throw new AwsException("NotFoundException",
                    "Resource " + arn + " was not found.", 404);
        }
    }

    // name is everything after the type's first slash and may itself contain one: a tenant ARN's
    // <name>/<tenantId> remainder passes through whole, decomposed by the tenant domain.
    private record ResourceRef(String account, String region, String type, String name) {}

    /**
     * AWS rejects a tag operation whose ARN carries a different account id before any region or
     * existence check (probe-confirmed): the account error wins even when the region is also
     * mismatched or the resource doesn't exist anywhere.
     */
    private void requireCallerAccount(ResourceRef ref) {
        String callerAccountId = regionResolver != null ? regionResolver.getAccountId() : defaultAccountId;
        if (!ref.account().equals(callerAccountId)) {
            throw new AwsException("BadRequestException",
                    "Operations on a resource created in a different account is not allowed", 400);
        }
    }

    private static ResourceRef parseSesArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("BadRequestException", "ResourceArn is required.", 400);
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException", "Invalid ARN: " + arn, 400);
        }
        if (!"ses".equals(parsed.service())) {
            throw new AwsException("BadRequestException",
                    "ResourceArn must be a SES ARN: " + arn, 400);
        }
        if (parsed.region().isEmpty() || parsed.accountId().isEmpty()) {
            throw new AwsException("BadRequestException",
                    "ResourceArn must include region and account: " + arn, 400);
        }
        String resource = parsed.resource();
        int slash = resource.indexOf('/');
        if (slash <= 0 || slash == resource.length() - 1) {
            throw new AwsException("BadRequestException", "Invalid ARN: " + arn, 400);
        }
        return new ResourceRef(parsed.accountId(), parsed.region(),
                resource.substring(0, slash), resource.substring(slash + 1));
    }

    // ──────────────────── Suppression (account attributes + list) ────────────────────
    // Storage lives in SesSuppressionService; the account attributes are read and written by the v2
    // controller directly, the list operations below keep the tenant routing here, and the send
    // filters (collectSuppressedReasons / resolveSuppressionReason) read entries back through it.

    // A TenantName routes each suppression-list operation to that tenant's own list (fully separate
    // from the account list on AWS); the reason/address validation still runs first, matching the
    // probed precedence where request validation precedes tenant existence.

    public void putSuppressedDestination(String region, String emailAddress, String reason) {
        putSuppressedDestination(region, emailAddress, reason, null);
    }

    public SuppressedDestination getSuppressedDestination(String region, String emailAddress) {
        return getSuppressedDestination(region, emailAddress, null);
    }

    public void deleteSuppressedDestination(String region, String emailAddress) {
        deleteSuppressedDestination(region, emailAddress, null);
    }

    public List<SuppressedDestination> listSuppressedDestinations(String region,
                                                                  List<String> reasonFilters) {
        return listSuppressedDestinations(region, reasonFilters, null);
    }

    public void putSuppressedDestination(String region, String emailAddress, String reason,
                                         String tenantName) {
        if (tenantName == null) {
            suppressionService.putSuppressedDestination(region, emailAddress, reason);
            return;
        }
        // The address and reason are validated before the tenant is resolved, keeping request
        // validation ahead of tenant existence for every member, as on the attribute operations.
        SesSuppressionService.normalizeSuppressionEmail(emailAddress);
        SesSuppressionService.validateSuppressionReason(reason, "reason", false);
        tenantService.runWithTenant(tenantName, region, tenant -> {
            suppressionService.putTenantSuppressedDestination(region, tenant.tenantId(),
                    tenantName, emailAddress, reason);
            return null;
        });
    }

    public SuppressedDestination getSuppressedDestination(String region, String emailAddress,
                                                          String tenantName) {
        if (tenantName == null) {
            return suppressionService.getSuppressedDestination(region, emailAddress);
        }
        SesSuppressionService.normalizeSuppressionEmail(emailAddress);
        return tenantService.runWithTenant(tenantName, region, tenant ->
                suppressionService.getTenantSuppressedDestination(region, tenant.tenantId(),
                        emailAddress));
    }

    public void deleteSuppressedDestination(String region, String emailAddress, String tenantName) {
        if (tenantName == null) {
            suppressionService.deleteSuppressedDestination(region, emailAddress);
            return;
        }
        SesSuppressionService.normalizeSuppressionEmail(emailAddress);
        tenantService.runWithTenant(tenantName, region, tenant -> {
            suppressionService.deleteTenantSuppressedDestination(region, tenant.tenantId(),
                    emailAddress);
            return null;
        });
    }

    public List<SuppressedDestination> listSuppressedDestinations(String region,
                                                                  List<String> reasonFilters,
                                                                  String tenantName) {
        if (tenantName == null) {
            return suppressionService.listSuppressedDestinations(region, reasonFilters);
        }
        SesSuppressionService.validateReasonFilters(reasonFilters);
        return tenantService.runWithTenant(tenantName, region, tenant ->
                suppressionService.listTenantSuppressedDestinations(region, tenant.tenantId(),
                        reasonFilters));
    }
}
