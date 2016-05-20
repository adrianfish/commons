package org.sakaiproject.commons.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.sakaiproject.commons.api.CommonsFunctions;
import org.sakaiproject.commons.api.CommonsManager;
import org.sakaiproject.commons.api.CommonsSecurityManager;
import org.sakaiproject.commons.api.PersistenceManager;
import org.sakaiproject.commons.api.QueryBean;
import org.sakaiproject.commons.api.SakaiProxy;
import org.sakaiproject.commons.api.XmlDefs;
import org.sakaiproject.commons.api.datamodel.Comment;
import org.sakaiproject.commons.api.datamodel.Post;
import org.sakaiproject.entity.api.Entity;
import org.sakaiproject.entity.api.HttpAccess;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.memory.api.Cache;
import org.sakaiproject.profile2.logic.ProfileConnectionsLogic;
import org.sakaiproject.profile2.model.BasicConnection;
import org.sakaiproject.profile2.util.ProfileConstants;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Adrian Fish (adrian.r.fish@gmail.com)
 */
@Setter @Slf4j
public class CommonsManagerImpl implements CommonsManager {

    private PersistenceManager persistenceManager;
    private CommonsSecurityManager commonsSecurityManager;
    private SakaiProxy sakaiProxy;
    private ProfileConnectionsLogic profileConnectionsLogic;

    public void init() {
        
        if (log.isDebugEnabled()) {
            log.debug("init()");
        }

        log.info("Registering Commons functions ...");

        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_POST_CREATE);
        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_POST_READ_ANY);
        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_POST_UPDATE_ANY);
        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_POST_UPDATE_OWN);
        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_POST_DELETE_ANY);
        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_POST_DELETE_OWN);
        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_COMMENT_CREATE);
        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_COMMENT_READ_ANY);
        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_COMMENT_READ_OWN);
        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_COMMENT_UPDATE_ANY);
        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_COMMENT_UPDATE_OWN);
        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_COMMENT_DELETE_ANY);
        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_COMMENT_DELETE_OWN);
        sakaiProxy.registerFunction(CommonsFunctions.COMMONS_MODIFY_PERMISSIONS);

        log.info("Registered Commons functions ...");

        sakaiProxy.registerEntityProducer(this);
    }

    private List<Post> getPosts(String siteId) throws Exception {
        QueryBean query = new QueryBean();
        query.siteId = siteId;
        return commonsSecurityManager.filter(persistenceManager.getAllPost(query), siteId, "SITE");
    }

    public List<Post> getPosts(QueryBean query) throws Exception {

        Cache cache = sakaiProxy.getCache(POST_CACHE);

        if (query.isUserSite) {
            System.out.println("USER");
            query.fromIds.add(query.callerId);
            List<BasicConnection> connections = profileConnectionsLogic.getBasicConnectionsForUser(sakaiProxy.getCurrentUserId());
            for (BasicConnection basicConnection : connections) {
                query.fromIds.add(basicConnection.getUuid());
            }
        }

        String key = (query.isUserSite) ? query.callerId : query.commonsId;

        List<Post> posts = (List<Post>) cache.get(key);
        if (posts == null) {
            if (log.isDebugEnabled()) log.debug("Cache miss or expired on id: " + key);
            List<Post> unfilteredPosts = persistenceManager.getAllPost(query, true);
            cache.put(key, unfilteredPosts);
            return commonsSecurityManager.filter(unfilteredPosts, query.siteId, query.embedder);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Cache hit on id: " + key);
            }
            return commonsSecurityManager.filter(posts, query.siteId, query.embedder);
        }
    }

    public Post savePost(Post post) {

        boolean isUserSite = sakaiProxy.isUserSite(post.getSiteId());

        try {
            Post newOrUpdatedPost = persistenceManager.savePost(post);
            if (newOrUpdatedPost != null) {
                removeContextIdFromCache(post.getCommonsId());
                return newOrUpdatedPost;
            } else {
                log.error("Failed to save post");
            }
        } catch (Exception e) {
            log.error("Caught exception whilst saving post", e);
        }

        return null;
    }

    public boolean deletePost(String postId) {

        try {
            Post post = persistenceManager.getPost(postId, false);
            if (commonsSecurityManager.canCurrentUserDeletePost(post)) {
                if (persistenceManager.deletePost(post)) {
                    // Invalidate all caches for this site
                    removeContextIdFromCache(post.getCommonsId());
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    public Comment saveComment(String commonsId, Comment comment) {

        try {
            Comment savedComment = persistenceManager.saveComment(comment);
            if (savedComment != null) {
                removeContextIdFromCache(commonsId);
                return savedComment;
            }
        } catch (Exception e) {
            log.error("Caught exception whilst saving comment", e);
        }

        return null;
    }

    public boolean deleteComment(String commonsId, String commentId) {

        try {
            if (persistenceManager.deleteComment(commentId)) {
                removeContextIdFromCache(commonsId);
                return true;
            }
        } catch (Exception e) {
            log.error("Caught exception whilst deleting comment.", e);
        }

        return false;
    }

    private String serviceName() {
        return CommonsManager.class.getName();
    }

    public String archive(String siteId, Document doc, Stack stack, String archivePath, List attachments) {

        if (log.isDebugEnabled()) {
            log.debug("archive(siteId:" + siteId + ",archivePath:" + archivePath + ")");
        }

        StringBuilder results = new StringBuilder();

        results.append(getLabel() + ": Started.\n");

        int postCount = 0;

        try {
            // start with an element with our very own (service) name
            Element element = doc.createElement(serviceName());
            element.setAttribute("version", "11.x");
            ((Element) stack.peek()).appendChild(element);
            stack.push(element);

            Element commons = doc.createElement("commons");
            List<Post> posts = getPosts(siteId);
            if (posts != null && posts.size() > 0) {
                for (Post post : posts) {
                    Element postElement = post.toXml(doc, stack);
                    commons.appendChild(postElement);
                    postCount++;
                }
            }

            ((Element) stack.peek()).appendChild(commons);
            stack.push(commons);

            stack.pop();

            results.append(getLabel() + ": Finished. " + postCount + " post(s) archived.\n");
        } catch (Exception any) {
            results.append(getLabel() + ": exception caught. Message: " + any.getMessage());
            log.warn(getLabel() + " exception caught. Message: " + any.getMessage());
        }

        stack.pop();

        return results.toString();
    }

    /**
     * From EntityProducer
     */
    public String merge(String siteId, Element root, String archivePath, String fromSiteId, Map attachmentNames, Map userIdTrans, Set userListAllowImport) {

        if (log.isDebugEnabled()) {
            log.debug("merge(siteId:" + siteId + ",root tagName:" + root.getTagName() + ",archivePath:" + archivePath + ",fromSiteId:" + fromSiteId);
        }

        StringBuilder results = new StringBuilder();

        int postCount = 0;

        NodeList postNodes = root.getElementsByTagName(XmlDefs.POST);
        final int numberPosts = postNodes.getLength();

        for (int i = 0; i < numberPosts; i++) {
            Node child = postNodes.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                log.error("Post nodes should be elements. Skipping ...");
                continue;
            }

            Element postElement = (Element) child;

            Post post = new Post();
            post.fromXml(postElement);
            post.setSiteId(siteId);

            savePost(post);

            for (Comment comment : post.getComments()) {
                comment.setPostId(post.getId());
                saveComment(siteId, comment);
            }

            postCount++;
        }

        results.append("Stored " + postCount + " posts.");

        return results.toString();
    }

    /**
     * From EntityProducer
     */
    public Entity getEntity(Reference ref) {

        if (log.isDebugEnabled()) {
            log.debug("getEntity(Ref ID:" + ref.getId() + ")");
        }

        Entity rv = null;

        try {
            String reference = ref.getReference();

            String[] parts = reference.split(Entity.SEPARATOR);

            if (parts.length == 5) {
                String postId = parts[4];
                rv = persistenceManager.getPost(postId, true);
            }
        } catch (Exception e) {
            log.warn("getEntity(): " + e);
        }

        return rv;
    }

    /**
     * From EntityProducer
     */
    public Collection getEntityAuthzGroups(Reference ref, String userId) {

        if (log.isDebugEnabled()) {
            log.debug("getEntityAuthzGroups(Ref ID:" + ref.getId() + "," + userId + ")");
        }

        List ids = new ArrayList();
        ids.add("/site/" + ref.getContext());
        return ids;
    }

    public String getEntityDescription(Reference arg0) {
        return null;
    }

    public ResourceProperties getEntityResourceProperties(Reference ref) {

        try {
            String reference = ref.getReference();

            int lastIndex = reference.lastIndexOf(Entity.SEPARATOR);
            String postId = reference.substring(lastIndex, reference.length() - lastIndex);
            Entity entity = persistenceManager.getPost(postId, false);
            return entity.getProperties();
        } catch (Exception e) {
            log.warn("getEntity(): " + e);
            return null;
        }
    }

    /**
     * From EntityProducer
     */
    public String getEntityUrl(Reference ref) {
        return getEntity(ref).getUrl();
    }

    /**
     * From EntityProducer
     */
    public HttpAccess getHttpAccess() {
        return null;
    }

    /**
     * From EntityProducer
     */
    public String getLabel() {
        return "commons";
    }

    /**
     * From EntityProducer
     */
    public boolean parseEntityReference(String referenceString, Reference reference) {

        String[] parts = referenceString.split(Entity.SEPARATOR);

        if (parts.length < 2 || !parts[1].equals("commons")) // Leading slash adds
                                                          // an empty element
            return false;

        if (parts.length == 2) {
            reference.set("sakai:commons", "", "", null, "");
            return true;
        }

        String siteId = parts[2];
        String subType = parts[3];
        String entityId = parts[4];

        if ("posts".equals(subType)) {
            reference.set("commons", "posts", entityId, null, siteId);
            return true;
        }

        return false;
    }

    public boolean willArchiveMerge() {
        return true;
    }

    public String getEntityPrefix() {
        return CommonsManager.ENTITY_PREFIX;
    }

    public boolean entityExists(String id) {

        String postId = id.substring(id.lastIndexOf(Entity.SEPARATOR));

        try {
            if (persistenceManager.postExists(postId))
                return true;
        } catch (Exception e) {
            log.error("entityExists threw an exception", e);
        }

        return false;
    }

    private void removeContextIdFromCache(String contextId) {
        sakaiProxy.getCache(POST_CACHE).remove(contextId);
    }
}