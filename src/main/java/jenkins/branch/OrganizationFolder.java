/*
 * The MIT License
 *
 * Copyright 2015 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.branch;

import antlr.ANTLRException;
import com.cloudbees.hudson.plugins.folder.AbstractFolderDescriptor;
import com.cloudbees.hudson.plugins.folder.ChildNameGenerator;
import com.cloudbees.hudson.plugins.folder.FolderIcon;
import com.cloudbees.hudson.plugins.folder.FolderIconDescriptor;
import com.cloudbees.hudson.plugins.folder.computed.ChildObserver;
import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import com.cloudbees.hudson.plugins.folder.computed.FolderComputation;
import com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger;
import com.cloudbees.hudson.plugins.folder.views.AbstractFolderViewHolder;
import com.thoughtworks.xstream.XStreamException;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.BulkChange;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.model.Action;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.Saveable;
import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.model.listeners.SaveableListener;
import hudson.util.DescribableList;
import hudson.util.LogTaskListener;
import hudson.util.PersistedList;
import hudson.util.StreamTaskListener;
import hudson.util.io.ReopenableRotatingFileOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;
import jenkins.scm.api.SCMEventListener;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMNavigatorDescriptor;
import jenkins.scm.api.SCMNavigatorEvent;
import jenkins.scm.api.SCMNavigatorOwner;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCategory;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceEvent;
import jenkins.scm.api.SCMSourceObserver;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.metadata.ObjectMetadataAction;
import jenkins.scm.impl.SingleSCMNavigator;
import jenkins.scm.impl.UncategorizedSCMSourceCategory;
import org.acegisecurity.AccessDeniedException;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkins.ui.icon.Icon;
import org.jenkins.ui.icon.IconSet;
import org.jenkins.ui.icon.IconSpec;
import org.jvnet.localizer.LocaleProvider;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import static jenkins.scm.api.SCMEvent.Type.CREATED;
import static jenkins.scm.api.SCMEvent.Type.UPDATED;

/**
 * A folder-like collection of {@link MultiBranchProject}s, one per repository.
 */
@Restricted(NoExternalUse.class) // not currently intended as an API
@SuppressWarnings({"unchecked", "rawtypes"}) // mistakes in various places
public final class OrganizationFolder extends ComputedFolder<MultiBranchProject<?,?>>
        implements SCMNavigatorOwner, IconSpec {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(MultiBranchProject.class.getName());
    /**
     * Our navigators.
     */
    private final DescribableList<SCMNavigator,SCMNavigatorDescriptor> navigators = new DescribableList<SCMNavigator, SCMNavigatorDescriptor>(this);
    /**
     * Our project factories.
     */
    private final DescribableList<MultiBranchProjectFactory,MultiBranchProjectFactoryDescriptor> projectFactories = new DescribableList<MultiBranchProjectFactory,MultiBranchProjectFactoryDescriptor>(this);

    /**
     * The persisted state maintained outside of the config file.
     *
     * @since 2.0
     */
    private transient /*almost final*/ State state = new State(this);

    /**
     * The navigator digest used to detect if we need to trigger a rescan on save.
     *
     * @since 2.0
     */
    private transient String navDigest;

    /**
     * The factory digest used to detect if we need to trigger a rescan on save.
     *
     * @since 2.0
     */
    private transient String facDigest;

    /**
     * {@inheritDoc}
     */
    public OrganizationFolder(ItemGroup parent, String name) {
        super(parent, name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreatedFromScratch() {
        super.onCreatedFromScratch();
        for (MultiBranchProjectFactoryDescriptor d : ExtensionList.lookup(MultiBranchProjectFactoryDescriptor.class)) {
            MultiBranchProjectFactory f = d.newInstance();
            if (f != null) {
                projectFactories.add(f);
            }
        }
        try {
            addTrigger(new PeriodicFolderTrigger("1d"));
        } catch (ANTLRException x) {
            throw new IllegalStateException(x);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onLoad(ItemGroup<? extends Item> parent, String name) throws IOException {
        super.onLoad(parent, name);
        navigators.setOwner(this);
        projectFactories.setOwner(this);
        if (!(getFolderViews() instanceof OrganizationFolderViewHolder)) {
            resetFolderViews();
        }
        if (!(getIcon() instanceof MetadataActionFolderIcon)) {
            setIcon(newDefaultFolderIcon());
        }
        if (state == null) {
            state = new State(this);
        }
        try {
            state.load();
        } catch (XStreamException | IOException e) {
            LOGGER.log(Level.WARNING, "Could not read persisted state, will be recovered on next index.", e);
            state.reset();
        }
        if (getComputation().getLogFile().isFile()) {
            // TODO find a more reliable way to detect if the folder has not been scanned since creation
            // Basically we want the first save after a config change to trigger a scan.
            // The above condition will cover the very first save, but will not cover the case of the configuration
            // being changed *by code not the user*, saved and then Jenkins restarted before the scan occurs.
            // Should not be a big deal as periodic scan will pick it up eventually and user can always manually force
            // the issue by triggering a manual scan
            try {
                navDigest = Util.getDigestOf(Items.XSTREAM2.toXML(navigators));
            } catch (XStreamException e) {
                navDigest = null;
            }
            try {
                facDigest = Util.getDigestOf(Items.XSTREAM2.toXML(projectFactories));
            } catch (XStreamException e) {
                facDigest = null;
            }
        }
    }

    @Override
    public MultiBranchProject<?, ?> getItem(String name) throws AccessDeniedException {
        if (name == null) {
            return null;
        }
        MultiBranchProject<?, ?> item = super.getItem(name);
        if (item != null) {
            return item;
        }
        if (name.indexOf('%') != -1) {
            String decoded = NameEncoder.decode(name);
            item = super.getItem(decoded);
            if (item != null) {
                return item;
            }
            // fall through for double decoded call paths // TODO is this necessary
        }
        return super.getItem(NameEncoder.encode(name));
    }

    /**
     * Returns the child job with the specified project name or {@code null} if no such child job exists.
     *
     * @param projectName the name of the project.
     * @return the child job or {@code null} if no such job exists or if the requesting user does ave permission to
     * view it.
     * @since 2.0.0
     */
    @edu.umd.cs.findbugs.annotations.CheckForNull
    public MultiBranchProject<?,?> getItemByProjectName(@NonNull String projectName) {
        return super.getItem(NameEncoder.encode(projectName));
    }

    /**
     * Returns {@code true} if this is a single origin {@link OrganizationFolder}.
     *
     * @return {@code true} if this is a single origin {@link OrganizationFolder}.
     */
    public boolean isSingleOrigin() {
        // JENKINS-41171 we expect everything except for rare legacy instances to be single origin.
        return navigators.size() == 1;
    }

    public DescribableList<SCMNavigator,SCMNavigatorDescriptor> getNavigators() {
        return navigators;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<SCMNavigator> getSCMNavigators() {
        return navigators;
    }

    public DescribableList<MultiBranchProjectFactory,MultiBranchProjectFactoryDescriptor> getProjectFactories() {
        return projectFactories;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void submit(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException, Descriptor.FormException {
        super.submit(req, rsp);
        navigators.rebuildHetero(req, req.getSubmittedForm(), ExtensionList.lookup(SCMNavigatorDescriptor.class), "navigators");
        projectFactories.rebuildHetero(req, req.getSubmittedForm(), ExtensionList.lookup(MultiBranchProjectFactoryDescriptor.class), "projectFactories");
        for (SCMNavigator n : navigators) {
            n.afterSave(this);
        }
        String navDigest;
        try {
            navDigest = Util.getDigestOf(Items.XSTREAM2.toXML(navigators));
        } catch (XStreamException e) {
            navDigest = null;
        }
        String facDigest;
        try {
            facDigest = Util.getDigestOf(Items.XSTREAM2.toXML(projectFactories));
        } catch (XStreamException e) {
            facDigest = null;
        }
        recalculateAfterSubmitted(!StringUtils.equals(navDigest, this.navDigest));
        recalculateAfterSubmitted(!StringUtils.equals(facDigest, this.facDigest));
        this.navDigest = navDigest;
        this.facDigest = facDigest;
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    protected FolderComputation<MultiBranchProject<?, ?>> createComputation(
            @CheckForNull FolderComputation<MultiBranchProject<?, ?>> previous) {
        return new OrganizationScan(OrganizationFolder.this, previous);
    }

    @Override
    public boolean isHasEvents() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void computeChildren(final ChildObserver<MultiBranchProject<?,?>> observer, final TaskListener listener) throws IOException, InterruptedException {
        // capture the current digests to prevent unnecessary rescan if re-saving after scan
        try {
            navDigest = Util.getDigestOf(Items.XSTREAM2.toXML(navigators));
        } catch (XStreamException e) {
            navDigest = null;
        }
        try {
            facDigest = Util.getDigestOf(Items.XSTREAM2.toXML(projectFactories));
        } catch (XStreamException e) {
            facDigest = null;
        }
        long start = System.currentTimeMillis();
        listener.getLogger().format("[%tc] Starting organization scan...%n", start);
        try {
            listener.getLogger().format("[%tc] Updating actions...%n", System.currentTimeMillis());
            Map<SCMNavigator, List<Action>> navigatorActions = new HashMap<>();
            for (SCMNavigator navigator : navigators) {
                List<Action> actions;
                try {
                    actions = navigator.fetchActions(this, null, listener);
                } catch (IOException e) {
                    e.printStackTrace(listener.error("[%tc] Could not refresh actions for navigator %s",
                            System.currentTimeMillis(), navigator));
                    // preserve previous actions if we have some transient error fetching now (e.g. API rate limit)
                    actions = Util.fixNull(state.getActions().get(navigator));
                }
                navigatorActions.put(navigator, actions);
            }
            // update any persistent actions for the SCMNavigator
            if (!navigatorActions.equals(state.getActions())) {
                boolean saveProject = false;
                for (List<Action> actions : navigatorActions.values()) {
                    for (Action a : actions) {
                        // undo any hacks that attached the contributed actions without attribution
                        saveProject = removeActions(a.getClass()) || saveProject;
                    }
                }
                BulkChange bc = new BulkChange(state);
                try {
                    state.setActions(navigatorActions);
                    try {
                        bc.commit();
                    } catch (IOException | RuntimeException e) {
                        e.printStackTrace(listener.error("[%tc] Could not persist folder level actions",
                                System.currentTimeMillis()));
                        throw e;
                    }
                    if (saveProject) {
                        try {
                            save();
                        } catch (IOException | RuntimeException e) {
                            e.printStackTrace(listener.error(
                                    "[%tc] Could not persist folder level configuration changes",
                                    System.currentTimeMillis()));
                            throw e;
                        }
                    }
                } finally {
                    bc.abort();
                }
            }
            for (SCMNavigator navigator : navigators) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                listener.getLogger().format("[%tc] Consulting %s%n", System.currentTimeMillis(),
                        navigator.getDescriptor().getDisplayName());
                try {
                    navigator.visitSources(new SCMSourceObserverImpl(listener, observer));
                } catch (IOException | InterruptedException | RuntimeException e) {
                    e.printStackTrace(listener.error("[%tc] Could not fetch sources from navigator %s",
                            System.currentTimeMillis(), navigator));
                    throw e;
                }
            }
        } finally {
            long end = System.currentTimeMillis();
            listener.getLogger().format("[%tc] Finished organization scan. Scan took %s%n", end,
                    Util.getTimeSpanString(end - start));

        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected AbstractFolderViewHolder newFolderViewHolder() {
        return new OrganizationFolderViewHolder(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected FolderIcon newDefaultFolderIcon() {
        return new MetadataActionFolderIcon();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getIconClassName() {
        String result;
        if (navigators.size() == 1) {
            result = navigators.get(0).getDescriptor().getIconClassName();
        } else {
            result = null;
            for (int i = 0; i < navigators.size(); i++) {
                String iconClassName = navigators.get(i).getDescriptor().getIconClassName();
                if (i == 0) {
                    result = iconClassName;
                } else if (!StringUtils.equals(result, iconClassName)) {
                    result = null;
                    break;
                }
            }
        }

        return result != null ? result : getDescriptor().getIconClassName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPronoun() {
        Set<String> result = new TreeSet<>();
        for (SCMNavigator navigator: navigators) {
            String pronoun = Util.fixEmptyAndTrim(navigator.getPronoun());
            if (pronoun != null) {
                result.add(pronoun);
            }
        }
        return result.isEmpty() ? super.getPronoun() : StringUtils.join(result, " / ");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<SCMSource> getSCMSources() {
        // Probably unused unless onSCMSourceUpdated implemented, but just in case:
        Set<SCMSource> result = new HashSet<SCMSource>();
        for (MultiBranchProject<?,?> child : getItems()) {
            result.addAll(child.getSCMSources());
        }
        return new ArrayList<SCMSource>(result);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SCMSource getSCMSource(String sourceId) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSCMSourceUpdated(SCMSource source) {
        // TODO possibly we should recheck whether this project remains valid
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SCMSourceCriteria getSCMSourceCriteria(SCMSource source) {
        return null;
    }

    /**
     * Will create an specialized view when there are no repositories or branches found, which contain a Jenkinsfile
     * or other MARKER file.
     */
    @Override
    public View getPrimaryView() {
        if (getItems().isEmpty()) {
            return getWelcomeView();
        }
        return super.getPrimaryView();
    }

    /**
     * Creates a place-holder view when there's no active repositories indexed.
     *
     * @return a place-holder view for when there's no active repositories indexed.
     */
    protected View getWelcomeView() {
        return new OrganizationFolderEmptyView(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public View getView(String name) {
        if (name.equals("Welcome")) {
            return getWelcomeView();
        } else {
            return super.getView(name);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDescription() {
        String description = super.getDescription();
        if (StringUtils.isNotBlank(description)) {
            return description;
        }
        ObjectMetadataAction action = getAction(ObjectMetadataAction.class);
        if (action != null) {
            return action.getObjectDescription();
        }
        return super.getDescription();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDisplayName() {
        String displayName = getDisplayNameOrNull();
        if (displayName == null) {
            ObjectMetadataAction action = getAction(ObjectMetadataAction.class);
            if (action != null && StringUtils.isNotBlank(action.getObjectDisplayName())) {
                return action.getObjectDisplayName();
            }
        }
        return super.getDisplayName();
    }

    /**
     * Our descriptor
     */
    @Extension
    public static class DescriptorImpl extends AbstractFolderDescriptor {

        /**
         * {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            if (Jenkins.getActiveInstance().getInitLevel().compareTo(InitMilestone.EXTENSIONS_AUGMENTED) > 0) {
                List<SCMNavigatorDescriptor> navs = remove(ExtensionList.lookup(SCMNavigatorDescriptor.class),
                        SingleSCMNavigator.DescriptorImpl.class);
                if (navs.size() == 1) {
                    return Messages.OrganizationFolder_DisplayName(StringUtils.defaultIfBlank(
                            navs.get(0).getPronoun(),
                            Messages.OrganizationFolder_DefaultPronoun())
                    );
                }
            }
            return Messages.OrganizationFolder_DisplayName(Messages._OrganizationFolder_DefaultPronoun());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public TopLevelItem newInstance(ItemGroup parent, String name) {
            return new OrganizationFolder(parent, name);
        }

        /**
         * Used to categorize {@link OrganizationFolder} instances.
         *
         * @return A string with the category identifier. {@code TopLevelItemDescriptor#getCategoryId()}
         */
        //@Override TODO once baseline is 2.x
        @NonNull
        public String getCategoryId() {
            return "nested-projects";
        }

        /**
         * A description of this {@link OrganizationFolder}.
         *
         * @return A string with the description. {@code TopLevelItemDescriptor#getDescription()}.
         */
        //@Override TODO once baseline is 2.x
        @NonNull
        public String getDescription() {
            if (Jenkins.getActiveInstance().getInitLevel().compareTo(InitMilestone.EXTENSIONS_AUGMENTED) > 0) {
                List<SCMNavigatorDescriptor> navs = remove(ExtensionList.lookup(SCMNavigatorDescriptor.class),
                        SingleSCMNavigator.DescriptorImpl.class);
                SCMSourceCategory uncategorized = genericScmSourceCategory(navs);

                Locale locale = LocaleProvider.getLocale();
                return Messages.OrganizationFolder_Description(orJoinDisplayName(ExtensionList.lookup(MultiBranchProjectDescriptor.class)),
                        uncategorized.getDisplayName().toString(locale).toLowerCase(locale), orJoinDisplayName(navs));
            }
            Locale locale = LocaleProvider.getLocale();
            return Messages.OrganizationFolder_Description(Messages.OrganizationFolder_DefaultProject(),
                    UncategorizedSCMSourceCategory.DEFAULT.getDisplayName().toString(locale).toLowerCase(locale),
                    Messages.OrganizationFolder_DefaultPronoun()
            );
        }

        //@Override TODO once baseline is 2.x
        public String getIconFilePathPattern() {
            List<SCMNavigatorDescriptor> descriptors =
                    remove(ExtensionList.lookup(SCMNavigatorDescriptor.class),
                            SingleSCMNavigator.DescriptorImpl.class);
            if (descriptors.size() == 1) {
                return descriptors.get(0).getIconFilePathPattern();
            } else {
                return "plugin/branch-api/images/:size/organization-folder.png";
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getIconClassName() {
            List<SCMNavigatorDescriptor> descriptors =
                    remove(ExtensionList.lookup(SCMNavigatorDescriptor.class),
                            SingleSCMNavigator.DescriptorImpl.class);
            if (descriptors.size() == 1) {
                return descriptors.get(0).getIconClassName();
            } else {
                return "icon-branch-api-organization-folder";
            }
        }

        /**
         * Joins the {@link Descriptor#getDisplayName()} in a chain of "or".
         * @param navs the {@link Descriptor}s.
         * @return a string representing a disjoint list of the display names.
         */
        private static String orJoinDisplayName(List<? extends Descriptor> navs) {
            String providers;
            switch (navs.size()) {
                case 1:
                    providers = navs.get(0).getDisplayName();
                    break;
                case 2:
                    providers = Messages.OrganizationFolder_OrJoin2(navs.get(0).getDisplayName(),
                            navs.get(1).getDisplayName());
                    break;
                case 3:
                    providers = Messages.OrganizationFolder_OrJoinN_Last(Messages.OrganizationFolder_OrJoinN_First(
                            navs.get(0).getDisplayName(), navs.get(1).getDisplayName()),
                            navs.get(2).getDisplayName());
                    break;
                default:
                    String wip = Messages.OrganizationFolder_OrJoinN_First(
                            navs.get(0).getDisplayName(), navs.get(1).getDisplayName());
                    for (int i = 2; i < navs.size() - 2; i++) {
                        wip = Messages.OrganizationFolder_OrJoinN_Middle(wip, navs.get(i).getDisplayName());
                    }
                    providers = Messages.OrganizationFolder_OrJoinN_Last(wip,
                            navs.get(navs.size() - 1).getDisplayName());
                    break;
            }
            return providers;
        }

        /**
         * Creates a filtered sublist.
         *
         * @param <T> the type to remove from the base list
         * @param base the base list
         * @param type the type to remove from the base list
         * @return the list will all instances of the supplied type removed.
         */
        @Nonnull
        public static <T> List<T> remove(@Nonnull Iterable<T> base, @Nonnull Class<? extends T> type) {
            List<T> r = new ArrayList<T>();
            for (T i : base) {
                if (!type.isInstance(i))
                    r.add(i);
            }
            return r;
        }

        /**
         * Gets the {@link SCMSourceCategory#isUncategorized()} of a list of {@link SCMNavigatorDescriptor} instances.
         * @param descriptors the {@link SCMNavigatorDescriptor} instances.
         * @return the {@link SCMSourceCategory}.
         */
        private SCMSourceCategory genericScmSourceCategory(List<? extends SCMNavigatorDescriptor> descriptors) {
            List<SCMSourceCategory> sourceCategories = new ArrayList<>();
            for (SCMNavigatorDescriptor d: descriptors) {
                sourceCategories.addAll(d.getCategories());
            }
            SCMSourceCategory uncategorized = UncategorizedSCMSourceCategory.DEFAULT;
            for (SCMSourceCategory c: SCMSourceCategory.simplify(SCMSourceCategory.addUncategorizedIfMissing(sourceCategories)).values()) {
                if (c.isUncategorized()) {
                    uncategorized = c;
                    break;
                }
            }
            return uncategorized;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public List<FolderIconDescriptor> getIconDescriptors() {
            return Collections.<FolderIconDescriptor>singletonList(
                    Jenkins.getActiveInstance().getDescriptorByType(MetadataActionFolderIcon.DescriptorImpl.class)
            );
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isIconConfigurable() {
            return false;
        }

        @Override
        @NonNull
        public final ChildNameGenerator<OrganizationFolder, ? extends TopLevelItem> childNameGenerator() {
            return ChildNameGeneratorImpl.INSTANCE;
        }

        static {
            IconSet.icons.addIcon(
                    new Icon("icon-branch-api-organization-folder icon-sm",
                            "plugin/branch-api/images/16x16/organization-folder.png",
                            Icon.ICON_SMALL_STYLE));
            IconSet.icons.addIcon(
                    new Icon("icon-branch-api-organization-folder icon-md",
                            "plugin/branch-api/images/24x24/organization-folder.png",
                            Icon.ICON_MEDIUM_STYLE));
            IconSet.icons.addIcon(
                    new Icon("icon-branch-api-organization-folder icon-lg",
                            "plugin/branch-api/images/32x32/organization-folder.png",
                            Icon.ICON_LARGE_STYLE));
            IconSet.icons.addIcon(
                    new Icon("icon-branch-api-organization-folder icon-xlg",
                            "plugin/branch-api/images/48x48/organization-folder.png",
                            Icon.ICON_XLARGE_STYLE));
        }
    }

    private static class ChildNameGeneratorImpl extends ChildNameGenerator<OrganizationFolder, MultiBranchProject<?,?>> {

        private static final ChildNameGeneratorImpl INSTANCE = new ChildNameGeneratorImpl();

        @Override
        @CheckForNull
        public String itemNameFromItem(@NonNull OrganizationFolder parent, @NonNull MultiBranchProject<?, ?> item) {
            ProjectNameProperty property = item.getProperties().get(ProjectNameProperty.class);
            if (property != null) {
                return NameEncoder.encode(property.getName());
            }
            String idealName = idealNameFromItem(parent, item);
            if (idealName != null) {
                return NameEncoder.encode(idealName);
            }
            return null;
        }

        @Override
        @CheckForNull
        public String dirNameFromItem(@NonNull OrganizationFolder parent, @NonNull MultiBranchProject<?, ?> item) {
            ProjectNameProperty property = item.getProperties().get(ProjectNameProperty.class);
            if (property != null) {
                return NameMangler.apply(property.getName());
            }
            String idealName = idealNameFromItem(parent, item);
            if (idealName != null) {
                return NameMangler.apply(idealName);
            }
            return null;
        }

        @Override
        @NonNull
        public String itemNameFromLegacy(@NonNull OrganizationFolder parent, @NonNull String legacyDirName) {
            return NameEncoder.decode(legacyDirName);
        }

        @Override
        @NonNull
        public String dirNameFromLegacy(@NonNull OrganizationFolder parent, @NonNull String legacyDirName) {
            return NameMangler.apply(NameEncoder.decode(legacyDirName));
        }

        @Override
        public void recordLegacyName(OrganizationFolder parent, MultiBranchProject<?, ?> item, String legacyDirName)
                throws IOException {
            item.addProperty(new ProjectNameProperty(legacyDirName));
        }
    }

    /**
     * Our scan.
     */
    public static class OrganizationScan extends FolderComputation<MultiBranchProject<?, ?>> {
        public OrganizationScan(OrganizationFolder folder, FolderComputation<MultiBranchProject<?, ?>> previous) {
            super(folder, previous);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return Messages.OrganizationFolder_OrganizationScan_displayName(getParent().getPronoun());
        }

        @Override
        public void run() {
            long start = System.currentTimeMillis();
            try {
                super.run();
            } finally {
                long end = System.currentTimeMillis();
                LOGGER.log(Level.INFO, "{0} #{1,time,yyyyMMdd.HHmmss} organization scan action completed: {2} in {3}",
                        new Object[]{
                                getParent().getFullName(), start, getResult(), Util.getTimeSpanString(end - start)
                        }
                );
            }
        }

    }

    /**
     * Listens for events from the SCM event system.
     *
     * @since 2.0
     */
    @Extension
    public static class SCMEventListenerImpl extends SCMEventListener {

        /**
         * The {@link TaskListener} for events that we cannot assign to an organization folder.
         * @return The {@link TaskListener} for events that we cannot assign to an organization folder.
         */
        @Restricted(NoExternalUse.class)
        public StreamTaskListener globalEventsListener() {
            File logsDir = new File(Jenkins.getActiveInstance().getRootDir(), "logs");
            if (!logsDir.isDirectory() && !logsDir.mkdirs()) {
                LOGGER.log(Level.WARNING, "Could not create logs directory: {0}", logsDir);
            }
            File eventsFile = new File(logsDir, OrganizationFolder.class.getName() + ".log");
            if (!eventsFile.isFile()) {
                File oldFile = new File(logsDir.getParent(), eventsFile.getName());
                if (oldFile.isFile()) {
                    if (!oldFile.renameTo(eventsFile)) {
                        FileUtils.deleteQuietly(oldFile);
                    }
                }
            }
            boolean rotate = eventsFile.length() > 30 * 1024;
            RewindableRotatingFileOutputStream os = new RewindableRotatingFileOutputStream(eventsFile, true, 5);
            if (rotate) {
                try {
                    os.rewind();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Could not rotate " + eventsFile, e);
                }
            }
            return new StreamBuildListener(os, Charsets.UTF_8);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized void onSCMHeadEvent(SCMHeadEvent<?> event) {
            try (StreamTaskListener global = globalEventsListener()) {
                global.getLogger().format("[%tc] Received %s %s event from %s with timestamp %tc%n",
                        System.currentTimeMillis(), event.getClass().getName(), event.getType().name(),
                        event.getOrigin(), event.getTimestamp());
                int matchCount = 0;
                if (CREATED == event.getType() || UPDATED == event.getType()) {
                    for (OrganizationFolder p : Jenkins.getActiveInstance().getAllItems(OrganizationFolder.class)) {
                        // we want to catch when a branch is created / updated and consequently becomes eligible
                        // against the criteria. First check if the event matches one of the navigators
                        SCMNavigator navigator = null;
                        for (SCMNavigator n : p.getSCMNavigators()) {
                            if (event.isMatch(n)) {
                                matchCount++;
                                global.getLogger().format("Found match against %s%n", p.getFullName());
                                navigator = n;
                                break;
                            }
                        }
                        if (navigator == null) {
                            continue;
                        }
                        // ok, now check if any of the sources are a match... if they are then this event is not our
                        // concern
                        for (SCMSource s : p.getSCMSources()) {
                            if (event.isMatch(s)) {
                                // already have a source that will see this
                                global.getLogger()
                                        .format("Project %s already has a corresponding sub-project%n",
                                                p.getFullName());
                                navigator = null;
                                break;
                            }
                        }
                        if (navigator != null) {
                            global.getLogger()
                                    .format("Project %s does not have a corresponding sub-project%n", p.getFullName());
                            TaskListener listener;
                            try {
                                listener = p.getComputation().createEventsListener();
                            } catch (IOException e) {
                                listener = new LogTaskListener(LOGGER, Level.FINE);
                            }
                            ChildObserver childObserver = p.createEventsChildObserver();
                            long start = System.currentTimeMillis();
                            listener.getLogger().format("[%tc] Received %s %s event from %s with timestamp %tc%n",
                                    start, event.getClass().getName(), event.getType().name(), event.getOrigin(),
                                    event.getTimestamp());
                            try {
                                navigator.visitSources(p.new SCMSourceObserverImpl(listener, childObserver, event),
                                        event);
                            } catch (IOException | InterruptedException e) {
                                e.printStackTrace(listener.error(e.getMessage()));
                            } finally {
                                long end = System.currentTimeMillis();
                                listener.getLogger().format(
                                        "[%tc] %s %s event from %s with timestamp %tc processed in %s%n",
                                        end, event.getClass().getName(), event.getType().name(),
                                        event.getOrigin(), event.getTimestamp(),
                                        Util.getTimeSpanString(end - start));
                            }
                        }
                    }
                }
                global.getLogger()
                        .format("[%tc] Finished processing %s %s event from %s with timestamp %tc. Matched %d.%n",
                                System.currentTimeMillis(), event.getClass().getName(), event.getType().name(),
                                event.getOrigin(), event.getTimestamp(), matchCount);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not close global event log file", e);
            }

        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized void onSCMNavigatorEvent(SCMNavigatorEvent<?> event) {
            try (StreamTaskListener global = globalEventsListener()) {
                global.getLogger().format("[%tc] Received %s %s event from %s with timestamp %tc%n",
                        System.currentTimeMillis(), event.getClass().getName(), event.getType().name(),
                        event.getOrigin(), event.getTimestamp());
                int matchCount = 0;
                if (UPDATED == event.getType()) {
                    Set<SCMNavigator> matches = new HashSet<>();
                    for (OrganizationFolder p : Jenkins.getActiveInstance().getAllItems(OrganizationFolder.class)) {
                        matches.clear();
                        for (SCMNavigator n : p.getSCMNavigators()) {
                            if (event.isMatch(n)) {
                                matches.add(n);
                            }
                        }
                        if (!matches.isEmpty()) {
                            matchCount++;
                            TaskListener listener;
                            try {
                                listener = p.getComputation().createEventsListener();
                            } catch (IOException e) {
                                listener = new LogTaskListener(LOGGER, Level.FINE);
                            }
                            Map<SCMNavigator, List<Action>> navigatorActions = new HashMap<>();
                            for (SCMNavigator navigator : matches) {
                                try {
                                    List<Action> newActions = navigator.fetchActions(p, event, listener);
                                    List<Action> oldActions = p.state.getActions(navigator);
                                    if (oldActions == null || !oldActions.equals(newActions)) {
                                        navigatorActions.put(navigator, newActions);
                                    }
                                } catch (IOException | InterruptedException e) {
                                    e.printStackTrace(listener.error("Could not fetch metadata from %s", navigator));
                                }
                            }
                            // update any persistent actions for the SCMNavigator
                            if (!navigatorActions.isEmpty()) {
                                boolean saveProject = false;
                                for (List<Action> actions : navigatorActions.values()) {
                                    for (Action a : actions) {
                                        // undo any hacks that attached the contributed actions without attribution
                                        saveProject = p.removeActions(a.getClass()) || saveProject;
                                    }
                                }
                                BulkChange bc = new BulkChange(p.state);
                                try {
                                    for (Map.Entry<SCMNavigator, List<Action>> entry : navigatorActions.entrySet()) {
                                        p.state.setActions(entry.getKey(), entry.getValue());
                                    }
                                    bc.commit();
                                    if (saveProject) {
                                        p.save();
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace(listener.error("Could not persist updated metadata"));
                                } finally {
                                    bc.abort();
                                }
                            }
                        }
                    }
                }
                global.getLogger()
                        .format("[%tc] Finished processing %s %s event from %s with timestamp %tc. Matched %d.%n",
                                System.currentTimeMillis(), event.getClass().getName(), event.getType().name(),
                                event.getOrigin(), event.getTimestamp(), matchCount);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not close global event log file", e);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized void onSCMSourceEvent(SCMSourceEvent<?> event) {
            try (StreamTaskListener global = globalEventsListener()) {
                global.getLogger().format("[%tc] Received %s %s event from %s with timestamp %tc%n",
                        System.currentTimeMillis(), event.getClass().getName(), event.getType().name(),
                        event.getOrigin(), event.getTimestamp());
                int matchCount = 0;
                if (CREATED == event.getType()) {
                    for (OrganizationFolder p : Jenkins.getActiveInstance().getAllItems(OrganizationFolder.class)) {
                        boolean haveMatch = false;
                        for (SCMNavigator n : p.getSCMNavigators()) {
                            if (event.isMatch(n)) {
                                global.getLogger().format("Found match against %s%n", p.getFullName());
                                haveMatch = true;
                                break;
                            }
                        }
                        if (haveMatch) {
                            matchCount++;
                            TaskListener listener;
                            try {
                                listener = p.getComputation().createEventsListener();
                            } catch (IOException e) {
                                listener = new LogTaskListener(LOGGER, Level.FINE);
                            }
                            ChildObserver childObserver = p.createEventsChildObserver();
                            long start = System.currentTimeMillis();
                            listener.getLogger().format("[%tc] Received %s %s event from %s with timestamp %tc%n",
                                    start, event.getClass().getName(), event.getType().name(),
                                    event.getOrigin(), event.getTimestamp());
                            try {
                                for (SCMNavigator n : p.getSCMNavigators()) {
                                    if (event.isMatch(n)) {
                                        try {
                                            n.visitSources(p.new SCMSourceObserverImpl(listener, childObserver), event);
                                        } catch (IOException e) {
                                            e.printStackTrace(listener.error(e.getMessage()));
                                        }
                                    }
                                }
                            } catch (InterruptedException e) {
                                e.printStackTrace(listener.error(e.getMessage()));
                            } finally {
                                long end = System.currentTimeMillis();
                                listener.getLogger().format(
                                        "[%tc] %s %s event from %s with timestamp %tc processed in %s%n",
                                        end, event.getClass().getName(), event.getType().name(),
                                        event.getOrigin(), event.getTimestamp(),
                                        Util.getTimeSpanString(end - start));
                            }

                        }
                    }
                }
                global.getLogger()
                        .format("[%tc] Finished processing %s %s event from %s with timestamp %tc. Matched %d.%n",
                                System.currentTimeMillis(), event.getClass().getName(), event.getType().name(),
                                event.getOrigin(), event.getTimestamp(), matchCount);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not close global event log file", e);
            }

        }
    }

    private class SCMSourceObserverImpl extends SCMSourceObserver {
        private final TaskListener listener;
        private final ChildObserver<MultiBranchProject<?, ?>> observer;
        private final SCMHeadEvent<?> event;

        public SCMSourceObserverImpl(TaskListener listener, ChildObserver<MultiBranchProject<?, ?>> observer) {
            this.listener = listener;
            this.observer = observer;
            this.event = null;
        }

        public SCMSourceObserverImpl(TaskListener listener,
                                     ChildObserver<MultiBranchProject<?, ?>> observer, SCMHeadEvent<?> event) {
            this.listener = listener;
            this.observer = observer;
            this.event = event;
        }

        @NonNull
        @Override
        public SCMSourceOwner getContext() {
            return OrganizationFolder.this;
        }

        @NonNull
        @Override
        public TaskListener getListener() {
            return listener;
        }

        @NonNull
        @Override
        public ProjectObserver observe(@NonNull final String projectName) {
            return new ProjectObserver() {
                List<SCMSource> sources = new ArrayList<SCMSource>();

                @Override
                public void addSource(@NonNull SCMSource source) {
                    sources.add(source);
                    source.setOwner(OrganizationFolder.this);
                }

                private List<BranchSource> createBranchSources() {
                    if (sources == null) {
                        throw new IllegalStateException();
                    }
                    List<BranchSource> branchSources = new ArrayList<BranchSource>();
                    for (SCMSource source : sources) {
                        // TODO do we want/need a more general BranchPropertyStrategyFactory?
                        branchSources.add(new BranchSource(source));
                    }
                    sources = null; // make sure complete gets called just once
                    return branchSources;
                }

                @Override
                public void addAttribute(@NonNull String key, Object value)
                        throws IllegalArgumentException, ClassCastException {
                    throw new IllegalArgumentException();
                }

                private boolean recognizes(Map<String, Object> attributes, MultiBranchProjectFactory candidateFactory)
                        throws IOException, InterruptedException {
                    return candidateFactory.recognizes(
                                    OrganizationFolder.this,
                                    projectName,
                                    sources,
                                    attributes,
                                    event,
                                    listener);
                }

                @Override
                public void complete() throws IllegalStateException, InterruptedException {
                    try {
                        MultiBranchProjectFactory factory = null;
                        Map<String, Object> attributes = Collections.<String, Object>emptyMap();
                        for (MultiBranchProjectFactory candidateFactory : projectFactories) {
                            if (recognizes(attributes, candidateFactory)) {
                                factory = candidateFactory;
                                break;
                            }
                        }
                        if (factory == null) {
                            return;
                        }
                        String folderName = NameEncoder.encode(projectName);
                        MultiBranchProject<?, ?> existing = observer.shouldUpdate(folderName);
                        if (existing != null) {
                            BulkChange bc = new BulkChange(existing);
                            try {
                                existing.setSourcesList(createBranchSources());
                                existing.setOrphanedItemStrategy(getOrphanedItemStrategy());
                                factory.updateExistingProject(existing, attributes, listener);
                                ProjectNameProperty property =
                                        existing.getProperties().get(ProjectNameProperty.class);
                                if (property == null || !projectName.equals(property.getName())) {
                                    existing.getProperties().remove(ProjectNameProperty.class);
                                    existing.addProperty(new ProjectNameProperty(projectName));
                                }
                            } finally {
                                bc.commit();
                            }
                            existing.scheduleBuild();
                            return;
                        }
                        if (!observer.mayCreate(folderName)) {
                            listener.getLogger().println("Ignoring duplicate child " + projectName + " named " + folderName);
                            return;
                        }
                        MultiBranchProject<?, ?> project;
                        try (ChildNameGenerator.Trace trace = ChildNameGenerator.beforeCreateItem(
                                OrganizationFolder.this, folderName, projectName
                        )) {
                            project = factory.createNewProject(
                                    OrganizationFolder.this, folderName, sources, attributes, listener
                            );
                        }
                        BulkChange bc = new BulkChange(project);
                        try {
                            if (!projectName.equals(folderName)) {
                                project.setDisplayName(projectName);
                            }
                            project.addProperty(new ProjectNameProperty(projectName));
                            project.setOrphanedItemStrategy(getOrphanedItemStrategy());
                            project.getSourcesList().addAll(createBranchSources());
                            try {
                                project.addTrigger(new PeriodicFolderTrigger("1d"));
                            } catch (ANTLRException x) {
                                throw new IllegalStateException(x);
                            }
                        } finally {
                            bc.commit();
                        }
                        observer.created(project);
                        project.scheduleBuild();
                    } catch (InterruptedException x) {
                        throw x;
                    } catch (Exception x) {
                        x.printStackTrace(listener.error("Failed to create or update a subproject " + projectName));
                    }
                }
            };
        }

        @Override
        public void addAttribute(@NonNull String key, Object value)
                throws IllegalArgumentException, ClassCastException {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Adds the {@link OrganizationFolder.State#getActions()} to {@link OrganizationFolder#getAllActions()}.
     *
     * @since 2.0
     */
    @Extension
    public static class StateActionFactory extends TransientActionFactory<OrganizationFolder> {

        @Override
        public Class<OrganizationFolder> type() {
            return OrganizationFolder.class;
        }

        @Nonnull
        @Override
        public Collection<? extends Action> createFor(@Nonnull OrganizationFolder target) {
            List<Action> result = new ArrayList<>();
            for (List<Action> actions: target.state.getActions().values()) {
                result.addAll(actions);
            }
            return result;
        }
    }

    /**
     * The persisted state.
     *
     * @since 2.0
     */
    private static class State implements Saveable {
        private final transient OrganizationFolder owner;
        /**
         * The {@link SCMNavigator#fetchActions(SCMNavigatorOwner, SCMNavigatorEvent, TaskListener)} for each {@link SCMNavigator} keyed by the digest of the {@link SCMNavigator}.
         */
        private final Map<String,List<Action>> actions = new HashMap<>();

        private State(OrganizationFolder owner) {
            this.owner = owner;
        }

        public synchronized void reset() {
            actions.clear();
        }

        public final XmlFile getStateFile() {
            return new XmlFile(Items.XSTREAM, new File(owner.getRootDir(), "state.xml"));
        }

        public synchronized void load() throws IOException {
            if (getStateFile().exists()) {
                getStateFile().unmarshal(this);
            }
        }

        /**
         * Save the settings to a file.
         */
        @Override
        public void save() throws IOException {
            synchronized (this) {
                if (BulkChange.contains(this)) {
                    return;
                }
                getStateFile().write(this);
            }
            SaveableListener.fireOnChange(this, getStateFile());
        }

        public List<Action> getActions(SCMNavigator navigator) {
            if (owner.getSCMNavigators().contains(navigator)) {
                return Collections.unmodifiableList(Util.fixNull(actions.get(navigator.getId())));
            }
            return null;
        }

        public void setActions(SCMNavigator navigator, List<Action> actions) {
            this.actions.put(navigator.getId(), new ArrayList<Action>(actions));
        }

        public Map<SCMNavigator, List<Action>> getActions() {
            List<SCMNavigator> navigators = owner.getSCMNavigators();
            Map<SCMNavigator, List<Action>> result = new HashMap<>(navigators.size());
            for (SCMNavigator navigator: navigators) {
                result.put(navigator, Collections.unmodifiableList(Util.fixNull(actions.get(navigator.getId()))));
            }
            return result;
        }

        public void setActions(Map<SCMNavigator, List<Action>> actions) {
            Set<String> keys = new HashSet<>();
            for (Map.Entry<SCMNavigator, List<Action>> entry: actions.entrySet()) {
                String id = entry.getKey().getId();
                this.actions.put(id, new ArrayList<Action>(Util.fixNull(entry.getValue())));
                keys.add(id);
            }
            this.actions.keySet().retainAll(keys);
        }
    }
}
