/*************************************************************************************
 * Copyright 2006, 2008 Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at
 *
 *       http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.

 *************************************************************************************/
package org.sakaiproject.commons.impl;

import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.fileupload.FileItem;

import org.sakaiproject.assignment.api.AssignmentService;
import org.sakaiproject.authz.api.*;
import org.sakaiproject.commons.api.CommonsConstants;
import org.sakaiproject.commons.api.CommonsFunctions;
import org.sakaiproject.commons.api.CommonsManager;
import org.sakaiproject.commons.api.SakaiProxy;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.content.api.ContentResourceEdit;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.entity.api.EntityProducer;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.event.api.EventTrackingService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.memory.api.Cache;
import org.sakaiproject.memory.api.MemoryService;
import org.sakaiproject.memory.api.SimpleConfiguration;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.site.api.ToolConfiguration;
import org.sakaiproject.tool.api.*;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.sakaiproject.util.FormattedText;
import org.sakaiproject.event.api.NotificationService;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Adrian Fish (adrian.r.fish@gmail.com)
 */
@Setter @Slf4j
public class SakaiProxyImpl implements SakaiProxy {

    private AuthzGroupService authzGroupService;
    private ContentHostingService contentHostingService;
    private EntityManager entityManager;
    private EventTrackingService eventTrackingService;
    private FunctionManager functionManager;
    private MemoryService memoryService;
    private SecurityService securityService;
    private SessionManager sessionManager;
    private ServerConfigurationService serverConfigurationService;
    private SiteService siteService;
    private ToolManager toolManager;
    private UserDirectoryService userDirectoryService;

    public void init() {
    }


    public Session getCurrentSession() {
        return sessionManager.getCurrentSession();
    }

    public String getCurrentSiteId() {
        return toolManager.getCurrentPlacement().getContext(); // equivalent to
    }

    public Site getSiteOrNull(String siteId) {

        Site site = null;

        try {
            site = siteService.getSite(siteId);
        } catch (IdUnusedException idue) {
            log.warn("No site with id '" + siteId + "'");
        }

        return site;
    }

    /**
     * {@inheritDoc}
     */
    public String getCurrentSiteLocale() {

        String siteId = toolManager.getCurrentPlacement().getContext();

        Site currentSite = getSiteOrNull(siteId);

        if (currentSite != null) {
            String locale = currentSite.getProperties().getProperty("locale_string");
            if (locale != null) {
                return locale;
            }
        }

        return null;
    }

    public Tool getCurrentTool() {
        return toolManager.getCurrentTool();
    }

    public String getCurrentToolId() {
        return toolManager.getCurrentPlacement().getId();
    }

    public String getCurrentUserId() {

        Session session = sessionManager.getCurrentSession();
        String userId = session.getUserId();
        return userId;
    }

    public ToolSession getCurrentToolSession() {
        return sessionManager.getCurrentToolSession();
    }

    public void setCurrentToolSession(ToolSession toolSession) {
        sessionManager.setCurrentToolSession(toolSession);
    }

    public String getDisplayNameForTheUser(String userId) {

        try {
            User sakaiUser = userDirectoryService.getUser(userId);
            return FormattedText.escapeHtmlFormattedText(sakaiUser.getDisplayName());
        } catch (Exception e) {
            return userId; // this can happen if the user does not longer exist
            // in the system
        }
    }

    public boolean isCurrentUserAdmin() {
        return securityService.isSuperUser();
    }

    public String getPortalUrl() {

        // don't use serverConfigurationService.getPortalUrl() as it can return
        // 'sakai-entitybroker-direct' instead of 'portal'
        String serverUrl = serverConfigurationService.getServerUrl();
        return serverUrl + serverConfigurationService.getString("portalPath");
    }

    public void registerEntityProducer(EntityProducer entityProducer) {
        entityManager.registerEntityProducer(entityProducer, "/commons");
    }

    public void registerFunction(String function) {

        List functions = functionManager.getRegisteredFunctions("commons.");

        if (!functions.contains(function)) {
            functionManager.registerFunction(function);
        }
    }

    public boolean isAllowedFunction(String function, String siteId) {

        try {
            Site site = siteService.getSite(siteId);
            Role role = site.getUserRole(getCurrentUserId());
            return isAllowedFunction(function, role);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isAllowedFunction(String function, Role role) {

        try {
            if (isCurrentUserAdmin()) {
                return true;
            }

            if (role == null) {
                return false;
            }

            return role.isAllowed(function);
        } catch (Exception e) {
            log.error("Caught exception while performing function test", e);
        }

        return false;
    }

    public void postEvent(String event, String reference, String siteId) {
        eventTrackingService.post(eventTrackingService.newEvent(event, reference, siteId, true, NotificationService.NOTI_OPTIONAL));
    }

    public Set<String> getSiteUsers(String siteId) {

        try {
            Site site = siteService.getSite(siteId);
            return site.getUsers();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public String getCommonsToolId(String siteId) {

        try {
            Site site = siteService.getSite(siteId);
            ToolConfiguration tc = site.getToolForCommonId("sakai.commons");
            return tc.getId();
        } catch (Exception e) {
            return "";
        }
    }

    public Set<String> getSitePermissionsForCurrentUser(String siteId, String embedder) {

        String userId = getCurrentUserId();

        if (userId == null) {
            throw new SecurityException("This action (userPerms) is not accessible to anon and there is no current user.");
        }

        Set<String> filteredFunctions = new TreeSet();

        if (securityService.isSuperUser(userId)) {
            // Special case for the super admin
            filteredFunctions.addAll(functionManager.getRegisteredFunctions("commons"));
        } else {
            AuthzGroup siteRealm = null;
            try {
                siteRealm = authzGroupService.getAuthzGroup("/site/" + siteId);
            } catch (Exception e) {
                log.error("Error calling authzGroupService.getAuthzGroup(\"/site/" + siteId + "\")", e);
            }

            Role siteRole = siteRealm.getUserRole(userId);

            if (siteService.getUserSiteId(userId).equals(siteId)) {
                // This is a my workspace. Make sure the basic set are allowed so that
                // the security manager can make the right decisions.
                if (!siteRole.isAllowed(CommonsFunctions.POST_CREATE)
                        || !siteRole.isAllowed(CommonsFunctions.POST_READ_ANY)
                        || !siteRole.isAllowed(CommonsFunctions.POST_UPDATE_OWN)
                        || !siteRole.isAllowed(CommonsFunctions.POST_DELETE_OWN)
                        || !siteRole.isAllowed(CommonsFunctions.COMMENT_CREATE)
                        || !siteRole.isAllowed(CommonsFunctions.COMMENT_READ_ANY)
                        || !siteRole.isAllowed(CommonsFunctions.COMMENT_UPDATE_OWN)
                        || !siteRole.isAllowed(CommonsFunctions.COMMENT_DELETE_OWN)) {

                    siteRole.allowFunction(CommonsFunctions.POST_CREATE);
                    siteRole.allowFunction(CommonsFunctions.POST_READ_ANY);
                    siteRole.allowFunction(CommonsFunctions.POST_UPDATE_OWN);
                    siteRole.allowFunction(CommonsFunctions.POST_DELETE_OWN);
                    siteRole.allowFunction(CommonsFunctions.COMMENT_CREATE);
                    siteRole.allowFunction(CommonsFunctions.COMMENT_READ_ANY);
                    siteRole.allowFunction(CommonsFunctions.COMMENT_UPDATE_OWN);
                    siteRole.allowFunction(CommonsFunctions.COMMENT_DELETE_OWN);

                    try {
                        authzGroupService.save(siteRealm);
                    } catch (Exception e) {
                        // This should never happen.
                        log.error("Exception while saving user workspace role " + siteRole.getId() + " in site " + siteId, e);
                    }
                }
            } else if (embedder.equals(CommonsConstants.ASSIGNMENT)) {
                if (siteRole.isAllowed(AssignmentService.SECURE_ADD_ASSIGNMENT_SUBMISSION)) {
                    filteredFunctions.add(CommonsFunctions.POST_CREATE);
                    filteredFunctions.add(CommonsFunctions.POST_READ_ANY);
                    filteredFunctions.add(CommonsFunctions.POST_UPDATE_OWN);
                    filteredFunctions.add(CommonsFunctions.POST_DELETE_OWN);
                    filteredFunctions.add(CommonsFunctions.COMMENT_CREATE);
                    filteredFunctions.add(CommonsFunctions.COMMENT_READ_ANY);
                    filteredFunctions.add(CommonsFunctions.COMMENT_UPDATE_OWN);
                    filteredFunctions.add(CommonsFunctions.COMMENT_DELETE_OWN);
                }

                if (siteRole.isAllowed(AssignmentService.SECURE_ADD_ASSIGNMENT)) {
                    filteredFunctions.add(CommonsFunctions.POST_CREATE);
                    filteredFunctions.add(CommonsFunctions.POST_READ_ANY);
                    filteredFunctions.add(CommonsFunctions.POST_DELETE_ANY);
                    filteredFunctions.add(CommonsFunctions.POST_UPDATE_OWN);
                    filteredFunctions.add(CommonsFunctions.POST_DELETE_OWN);
                    filteredFunctions.add(CommonsFunctions.COMMENT_CREATE);
                    filteredFunctions.add(CommonsFunctions.COMMENT_READ_ANY);
                    filteredFunctions.add(CommonsFunctions.COMMENT_UPDATE_OWN);
                    filteredFunctions.add(CommonsFunctions.COMMENT_DELETE_OWN);
                    filteredFunctions.add(CommonsFunctions.COMMENT_DELETE_ANY);
                }

                return filteredFunctions;
            }

            Set<String> functions = siteRole.getAllowedFunctions();

            AuthzGroup siteHelperRealm = null;
            try {
                siteHelperRealm = authzGroupService.getAuthzGroup("!site.helper");
            } catch (Exception e) {
                log.error("Error calling authzGroupService.getAuthzGroup(\"!site.helper\")", e);
            }
            if (siteHelperRealm != null) {
                Role siteHelperRole = siteHelperRealm.getRole(siteRole.getId());
                if (siteHelperRole != null) {
                    // Merge in all the functions from the same role in !site.helper
                    functions.addAll(siteHelperRole.getAllowedFunctions());
                }
            }

            filteredFunctions.addAll(functions.stream().filter(f -> f.startsWith("commons")).collect(Collectors.toSet()));

            if (functions.contains("site.upd")) {
                filteredFunctions.add(CommonsFunctions.MODIFY_PERMISSIONS);
            }
        }

        return filteredFunctions;
    }

    public Map<String, Set<String>> getSitePermissions(String siteId) {

        Map<String, Set<String>> perms = new HashMap();

        String userId = getCurrentUserId();

        if (userId == null) {
            throw new SecurityException("This action (perms) is not accessible to anon and there is no current user.");
        }

        try {
            Site site = siteService.getSite(siteId);

            for (Role role : site.getRoles()) {
                Set<String> functions = role.getAllowedFunctions();
                perms.put(role.getId(), functions.stream().filter(f -> f.startsWith("commons")).collect(Collectors.toSet()));
            }
        } catch (Exception e) {
            log.error("Failed to get current site permissions.", e);
        }

        return perms;
    }

    public boolean setPermissionsForSite(String siteId, Map<String, Object> params) {

        String userId = getCurrentUserId();

        if (userId == null)
            throw new SecurityException("This action (setPerms) is not accessible to anon and there is no current user.");

        Site site = null;

        try {
            site = siteService.getSite(siteId);
        } catch (IdUnusedException ide) {
            log.warn(userId + " attempted to update COMMONS permissions for unknown site " + siteId);
            return false;
        }

        boolean admin = securityService.isSuperUser(userId);

        try {

            AuthzGroup authzGroup = authzGroupService.getAuthzGroup(site.getReference());

            // admin can update permissions. check for anyone else
            if (!securityService.isSuperUser()) {

                Role siteRole = authzGroup.getUserRole(userId);
                AuthzGroup siteHelperAuthzGroup = authzGroupService.getAuthzGroup("!site.helper");
                Role siteHelperRole = siteHelperAuthzGroup.getRole(siteRole.getId());

                if (!siteRole.isAllowed(CommonsFunctions.MODIFY_PERMISSIONS) && !siteRole.isAllowed("site.upd")) {
                    if (siteHelperRole == null || !siteHelperRole.isAllowed(CommonsFunctions.MODIFY_PERMISSIONS)) {
                        log.warn(userId + " attempted to update COMMONS permissions for site " + site.getTitle());
                        return false;
                    }
                }
            }

            boolean changed = false;

            for (String name : params.keySet()) {
                if (!name.contains(":")) {
                    continue;
                }

                String value = (String) params.get(name);

                String roleId = name.substring(0, name.indexOf(":"));

                Role role = authzGroup.getRole(roleId);
                if (role == null) {
                    throw new IllegalArgumentException("Invalid role id '" + roleId + "' provided in POST parameters.");
                }
                String function = name.substring(name.indexOf(":") + 1);

                if ("true".equals(value)) {
                    role.allowFunction(function);
                } else {
                    role.disallowFunction(function);
                }

                changed = true;
            }

            if (changed) {
                try {
                    authzGroupService.save(authzGroup);
                } catch (AuthzPermissionException ape) {
                    throw new SecurityException("The permissions for this site (" + siteId + ") cannot be updated by the current user.");
                }
            }

            return true;
        } catch (GroupNotDefinedException gnde) {
            log.error("No realm defined for site (" + siteId + ").", gnde);
        }

        return false;
    }

    public Cache getCache(String cache) {

        try {
            return memoryService.getCache(cache);
        } catch (Exception e) {
            log.error("Exception whilst retrieving '" + cache + "' cache. Returning null ...", e);
            return null;
        }
    }

    public boolean isUserSite(String siteId) {
        return siteService.isUserSite(siteId);
    }

    public void addObserver(Observer observer) {
        eventTrackingService.addObserver(observer);
    }

    public String storeFile(FileItem fileItem, String siteId) {

        try {
            String fileName = fileItem.getName();
            int lastIndexOf = fileName.lastIndexOf("/");
            if (lastIndexOf != -1 && (fileName.length() > lastIndexOf + 1)) {
                fileName = fileName.substring(lastIndexOf + 1);
            }
            String suffix = "";
            lastIndexOf = fileName.lastIndexOf(".");
            if (lastIndexOf != -1 && (fileName.length() > lastIndexOf + 1)) {
                suffix = fileName.substring(lastIndexOf + 1);
                fileName = fileName.substring(0, lastIndexOf);
            }
            ContentResourceEdit edit
                = contentHostingService.addResource("/group/" + siteId + "/", fileName, suffix , 2);
            edit.setContent(fileItem.getInputStream());
            contentHostingService.commitResource(edit);
            return edit.getUrl();
        } catch (Exception e) {
            log.error("Failed to store file.", e);
            return null;
        }
    }
}
