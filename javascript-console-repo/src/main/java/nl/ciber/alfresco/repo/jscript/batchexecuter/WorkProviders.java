package nl.ciber.alfresco.repo.jscript.batchexecuter;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.batch.BatchProcessWorkProvider;
import org.alfresco.repo.jscript.ScriptNode;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.namespace.RegexQNamePattern;
import org.apache.commons.logging.Log;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.Scriptable;

import java.util.*;

/**
 * Container class for all work providers used by
 * {@link nl.ciber.alfresco.repo.jscript.batchexecuter.ScriptBatchExecuter}.
 *
 * @author Bulat Yaminov
 */
public class WorkProviders {

//    private static final Log logger = LogFactory.getLog(WorkProviders.class);

    public interface NodeOrBatchWorkProviderFactory<T> {
        CancellableWorkProvider<Object> newNodesWorkProvider(T data, int batchSize);
        CancellableWorkProvider<List<Object>> newBatchesWorkProvider(T data, int batchSize);
        String describe(T data);
    }

    /**
     * WorkProvider which can be notified to stop giving any new work packages.
     * This is needed to cancel batch job, as we don't have access to
     * {@link org.alfresco.repo.batch.BatchProcessor}'s ExecuterService.
     */
    public interface CancellableWorkProvider<T> extends BatchProcessWorkProvider<T> {
        /**
         * Cancels BatchProcessWorkProvider from giving any more batches.
         * @return true if canceled, false if work provider was already canceled or finished before.
         */
        boolean cancel();
    }

    private static abstract class AbstractCancellableWorkProvider<T> implements CancellableWorkProvider<T> {
        private boolean canceled = false;

        @Override
        public synchronized boolean cancel() {
            if (canceled || !hasMoreWork()) {
                return false;
            }
            canceled = true;
            return true;
        }

        @Override
        public final synchronized Collection<T> getNextWork() {
            if (canceled) {
                return Collections.emptyList();
            } else {
                return doGetNextWork();
            }
        }

        protected abstract boolean hasMoreWork();

        protected abstract Collection<T> doGetNextWork();
    }

    public static class CollectionWorkProviderFactory implements NodeOrBatchWorkProviderFactory<Collection<Object>> {
        private static CollectionWorkProviderFactory INSTANCE = new CollectionWorkProviderFactory();

        public static CollectionWorkProviderFactory getInstance() {
            return INSTANCE;
        }

        @Override
        public CancellableWorkProvider<Object> newNodesWorkProvider(Collection<Object> items, int batchSize) {
            return new CollectionWorkProvider(items, batchSize);
        }

        @Override
        public CancellableWorkProvider<List<Object>> newBatchesWorkProvider(Collection<Object> items, int batchSize) {
            return new CollectionOfBatchesWorkProvider(items, batchSize);
        }

        @Override
        public String describe(Collection<Object> data) {
            return String.format("collection of %d nodes", data.size());
        }

        private class CollectionWorkProvider extends AbstractCancellableWorkProvider<Object> {

            private int itemsSize;
            private Iterator<Object> iterator;
            private int batchSize;

            public CollectionWorkProvider(Collection<Object> items, int batchSize) {
                this.itemsSize = items.size();
                this.batchSize = batchSize;
                this.iterator = items.iterator();
            }

            @Override
            public int getTotalEstimatedWorkSize() {
                return itemsSize;
            }

            @Override
            protected boolean hasMoreWork() {
                return iterator.hasNext();
            }

            @Override
            public Collection<Object> doGetNextWork() {
                /* Actually it is not needed to give work packages of fixed size here,
                 * but it is better for cancellation behavior */
                List<Object> batch = new ArrayList<>(batchSize);
                while (iterator.hasNext() && batch.size() < batchSize) {
                    batch.add(iterator.next());
                }
                return batch;
            }
        }

        private class CollectionOfBatchesWorkProvider extends AbstractCancellableWorkProvider<List<Object>> {

            private Iterator<Object> iterator;
            private int batchSize;
            private int fullSize;

            public CollectionOfBatchesWorkProvider(Collection<Object> items, int batchSize) {
                this.iterator = items.iterator();
                this.batchSize = batchSize;
                this.fullSize = new Double(Math.ceil(1.0d * items.size() / batchSize)).intValue();
            }

            @Override
            public int getTotalEstimatedWorkSize() {
                return fullSize;
            }

            @Override
            protected boolean hasMoreWork() {
                return iterator.hasNext();
            }

            @Override
            public Collection<List<Object>> doGetNextWork() {
                // Return just one batch wrapped in a singleton collection
                List<Object> batch = new ArrayList<>();
                while (iterator.hasNext() && batch.size() < batchSize) {
                    batch.add(iterator.next());
                }
                if (!batch.isEmpty()) {
                    return Collections.singletonList(batch);
                } else {
                    return Collections.emptyList();
                }
            }
        }
    }

    public static class FolderBrowsingWorkProviderFactory implements NodeOrBatchWorkProviderFactory<NodeRef> {

        private ServiceRegistry sr;
        private NodeService ns;
        private DictionaryService ds;
        private Log logger;
        private Scriptable scope;

        public FolderBrowsingWorkProviderFactory(ServiceRegistry sr, Scriptable scope, Log logger) {
            this.sr = sr;
            this.ns = sr.getNodeService();
            this.ds = sr.getDictionaryService();
            this.scope = scope;
            this.logger = logger;
        }

        @Override
        public CancellableWorkProvider<Object> newNodesWorkProvider(NodeRef root, int batchSize) {
            return new FolderBrowsingWorkProvider(root);
        }

        @Override
        public CancellableWorkProvider<List<Object>> newBatchesWorkProvider(NodeRef root, int batchSize) {
            return new FolderBrowsingInBatchesWorkProvider(root, batchSize);
        }

        @Override
        public String describe(NodeRef nodeRef) {
            String name = ns.exists(nodeRef) ?
                    (String) ns.getProperty(nodeRef, ContentModel.PROP_NAME) :
                    "deleted";
            return String.format("folder %s recursively", name);
        }

        private class FolderBrowsingWorkProvider extends AbstractCancellableWorkProvider<Object> {

            private Stack<NodeRef> stack = new Stack<>();

            private FolderBrowsingWorkProvider(NodeRef root) {
                stack.push(root);
            }

            @Override
            public int getTotalEstimatedWorkSize() {
                // we cannot quickly estimate how many recursive children a folder has
                return -1;
            }

            @Override
            protected boolean hasMoreWork() {
                return !stack.isEmpty();
            }

            /** Returns just one node at a time */
            @Override
            public Collection<Object> doGetNextWork() {
                NodeRef node = pop();
                if (node == null) {
                    return Collections.emptyList();
                } else {
                    return Collections.<Object>singletonList(convertToJS(node));
                }
            }

            protected NativeJavaObject convertToJS(NodeRef node) {
                ScriptNode scriptNode = new ScriptNode(node, sr, scope);
                return new NativeJavaObject(scope, scriptNode, ScriptNode.class);
            }

            protected NodeRef pop() {
                if (stack.isEmpty()) {
                    return null;
                }
                NodeRef head = stack.pop();
                if (ds.isSubClass(ns.getType(head), ContentModel.TYPE_FOLDER)) {
                    if (logger.isTraceEnabled()) {
                        logger.trace("fetching children of " + head);
                    }
                    List<ChildAssociationRef> children = ns.getChildAssocs(
                            head, ContentModel.ASSOC_CONTAINS, RegexQNamePattern.MATCH_ALL);
                    // Add to stack so that first child would appear as the head
                    for (int i = children.size() - 1; i >= 0; i--) {
                        stack.push(children.get(i).getChildRef());
                    }
                }
                return head;
            }
        }

        private class FolderBrowsingInBatchesWorkProvider extends AbstractCancellableWorkProvider<List<Object>> {

            private FolderBrowsingWorkProvider browser;
            private int batchSize;

            private FolderBrowsingInBatchesWorkProvider(NodeRef root, int batchSize) {
                this.browser = new FolderBrowsingWorkProvider(root);
                this.batchSize = batchSize;
            }

            @Override
            public int getTotalEstimatedWorkSize() {
                return browser.getTotalEstimatedWorkSize();
            }

            @Override
            protected boolean hasMoreWork() {
                return browser.hasMoreWork();
            }

            /** Returns just one batch wrapped in a collection */
            @Override
            public Collection<List<Object>> doGetNextWork() {
                List<Object> batch = new ArrayList<>();
                while (!browser.stack.isEmpty() && batch.size() < batchSize) {
                    batch.add(browser.convertToJS(browser.pop()));
                }
                if (!batch.isEmpty()) {
                    return Collections.singletonList(batch);
                } else {
                    return Collections.emptyList();
                }
            }
        }
    }
}