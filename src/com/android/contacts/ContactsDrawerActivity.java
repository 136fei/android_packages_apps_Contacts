/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.contacts;

import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.support.annotation.LayoutRes;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.ViewGroup;

import com.android.contacts.common.ContactsUtils;
import com.android.contacts.common.compat.BlockedNumberContractCompat;
import com.android.contacts.common.compat.TelecomManagerUtil;
import com.android.contacts.common.list.ContactListFilter;
import com.android.contacts.common.list.ContactListFilterController;
import com.android.contacts.common.preference.ContactsPreferenceActivity;
import com.android.contacts.common.util.AccountFilterUtil;
import com.android.contacts.common.util.ImplicitIntentsUtil;
import com.android.contacts.common.util.ViewUtil;
import com.android.contacts.editor.ContactEditorFragment;
import com.android.contacts.group.GroupListItem;
import com.android.contacts.group.GroupUtil;
import com.android.contacts.group.GroupsFragment;
import com.android.contacts.group.GroupsFragment.GroupsListener;
import com.android.contacts.interactions.AccountFiltersFragment;
import com.android.contacts.interactions.AccountFiltersFragment.AccountFiltersListener;
import com.android.contacts.quickcontact.QuickContactActivity;
import com.android.contacts.util.PhoneCapabilityTester;
import com.android.contactsbind.Assistants;
import com.android.contactsbind.HelpUtils;

import java.util.List;

/**
 * A common superclass for Contacts activities with a navigation drawer.
 */
public abstract class ContactsDrawerActivity extends AppCompatContactsActivity implements
        AccountFiltersListener,
        GroupsListener,
        NavigationView.OnNavigationItemSelectedListener {

    protected static String TAG = "ContactsDrawerActivity";

    protected static final String GROUPS_TAG = "groups";
    protected static final String FILTERS_TAG = "filters";

    protected ContactListFilterController mContactListFilterController;
    protected DrawerLayout mDrawer;
    protected Toolbar mToolbar;
    protected NavigationView mNavigationView;
    protected GroupsFragment mGroupsFragment;
    protected AccountFiltersFragment mAccountFiltersFragment;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        mContactListFilterController = ContactListFilterController.getInstance(this);
        mContactListFilterController.checkFilterValidity(false);

        super.setContentView(R.layout.contacts_drawer_activity);

        // Set up the action bar.
        mToolbar = getView(R.id.toolbar);
        setSupportActionBar(mToolbar);

        // Add shadow under toolbar.
        ViewUtil.addRectangularOutlineProvider(findViewById(R.id.toolbar_parent), getResources());

        // Set up hamburger button.
        mDrawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        final ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, mDrawer, mToolbar,
                R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        mDrawer.setDrawerListener(toggle);
        toggle.syncState();

        // Set up hamburger menu items.
        mNavigationView = (NavigationView) findViewById(R.id.nav_view);
        mNavigationView.setNavigationItemSelectedListener(this);

        final Menu menu = mNavigationView.getMenu();
        final boolean showBlockedNumbers = PhoneCapabilityTester.isPhone(this)
                && ContactsUtils.FLAG_N_FEATURE
                && BlockedNumberContractCompat.canCurrentUserBlockNumbers(this);

        if (!showBlockedNumbers) {
            menu.removeItem(R.id.nav_blocked_numbers);
        }

        if (Assistants.getDuplicatesActivityIntent(this) == null) {
            menu.removeItem(R.id.nav_find_duplicates);
        }

        if (!HelpUtils.isHelpAndFeedbackAvailable()) {
            menu.removeItem(R.id.nav_help);
        }

        loadGroupsAndFilters();
    }

    // Set up fragment manager to load groups and filters.
    protected void loadGroupsAndFilters() {
        final FragmentManager fragmentManager = getFragmentManager();
        final FragmentTransaction transaction = fragmentManager.beginTransaction();
        addGroupsAndFiltersFragments(transaction);
        transaction.commitAllowingStateLoss();
        fragmentManager.executePendingTransactions();
    }

    @Override
    public void setContentView(@LayoutRes int layoutResID) {
        final ViewGroup parent = (ViewGroup) findViewById(R.id.content_frame);
        if (parent != null) {
            parent.removeAllViews();
        }
        LayoutInflater.from(this).inflate(layoutResID, parent);
    }

    protected void addGroupsAndFiltersFragments(FragmentTransaction transaction) {
        final FragmentManager fragmentManager = getFragmentManager();
        mGroupsFragment = (GroupsFragment) fragmentManager.findFragmentByTag(GROUPS_TAG);
        mAccountFiltersFragment = (AccountFiltersFragment)
                fragmentManager.findFragmentByTag(FILTERS_TAG);

        if (mGroupsFragment == null && ContactsUtils.areGroupWritableAccountsAvailable(this)) {
            mGroupsFragment = new GroupsFragment();
            transaction.add(mGroupsFragment, GROUPS_TAG);
        }

        if (mAccountFiltersFragment == null) {
            mAccountFiltersFragment = new AccountFiltersFragment();
            transaction.add(mAccountFiltersFragment, FILTERS_TAG);
        }

        if (ContactsUtils.areGroupWritableAccountsAvailable(this) && mGroupsFragment != null) {
            mGroupsFragment.setListener(this);
        }
        mAccountFiltersFragment.setListener(this);
    }

    @Override
    public void onGroupsLoaded(List<GroupListItem> groupListItems) {
        final Menu menu = mNavigationView.getMenu();
        final MenuItem groupsMenuItem = menu.findItem(R.id.nav_groups);
        final SubMenu subMenu = groupsMenuItem.getSubMenu();
        subMenu.removeGroup(R.id.nav_groups_items);

        if (groupListItems != null) {
            // Add each group
            for (final GroupListItem groupListItem : groupListItems) {
                if (GroupUtil.isEmptyFFCGroup(groupListItem)) {
                    continue;
                }
                final String title = groupListItem.getTitle();
                final MenuItem menuItem =
                        subMenu.add(R.id.nav_groups_items, Menu.NONE, Menu.NONE, title);
                menuItem.setIcon(R.drawable.ic_menu_label);
                menuItem.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        onGroupMenuItemClicked(groupListItem.getGroupId());
                        return true;
                    }
                });
            }
        }

        // Don't show "Create new..." menu if there's no group-writable accounts available.
        if (!ContactsUtils.areGroupWritableAccountsAvailable(this)) {
            return;
        }

        // Create a menu item in the sub menu to add new groups
        final MenuItem menuItem = subMenu.add(R.id.nav_groups_items, Menu.NONE, Menu.NONE,
                getString(R.string.menu_new_group_action_bar));
        menuItem.setIcon(R.drawable.ic_add);
        menuItem.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                onCreateGroupMenuItemClicked();
                return true;
            }
        });
    }

    protected void onGroupMenuItemClicked(long groupId) {
        final Intent intent = GroupUtil.createViewGroupIntent(this, groupId);
        startActivity(intent);
    }

    protected void onCreateGroupMenuItemClicked() {
        startActivity(GroupUtil.createAddGroupIntent(this));
        mDrawer.closeDrawer(GravityCompat.START);
    }

    @Override
    public void onFiltersLoaded(List<ContactListFilter> accountFilterItems) {
        final Menu menu = mNavigationView.getMenu();
        final MenuItem filtersMenuItem = menu.findItem(R.id.nav_filters);
        final SubMenu subMenu = filtersMenuItem.getSubMenu();
        subMenu.removeGroup(R.id.nav_filters_items);

        if (accountFilterItems == null || accountFilterItems.size() < 2) {
            return;
        }

        for (int i = 0; i < accountFilterItems.size(); i++) {
            final ContactListFilter filter = accountFilterItems.get(i);
            final String accountName = filter.accountName;
            final MenuItem menuItem = subMenu.add(R.id.nav_filters_items, Menu.NONE, Menu.NONE,
                    accountName);
            final Intent intent = new Intent();
            intent.putExtra(AccountFilterUtil.EXTRA_CONTACT_LIST_FILTER, filter);
            menuItem.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    mDrawer.closeDrawer(GravityCompat.START);
                    AccountFilterUtil.handleAccountFilterResult(mContactListFilterController,
                            AppCompatActivity.RESULT_OK, intent);
                    if (shouldFinish()) {
                        finish();
                    }
                    return true;
                }
            });
            menuItem.setIcon(filter.icon);
            // Get rid of the default memu item overlay and show original account icons.
            menuItem.getIcon().setColorFilter(Color.TRANSPARENT, PorterDuff.Mode.SRC_ATOP);
        }
    }

    /**
     * @return true if the child activity should finish after launching another activity.
     */
    protected abstract boolean shouldFinish();

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        final int id = item.getItemId();

        if (id == R.id.nav_settings) {
            startActivity(createPreferenceIntent());
        } else if (id == R.id.nav_help) {
            HelpUtils.launchHelpAndFeedbackForMainScreen(this);
        } else if (id == R.id.nav_all_contacts) {
            switchToAllContacts();
        } else if (id == R.id.nav_blocked_numbers) {
            final Intent intent = TelecomManagerUtil.createManageBlockedNumbersIntent(
                    (TelecomManager) getSystemService(Context.TELECOM_SERVICE));
            ImplicitIntentsUtil.startActivityInApp(this, intent);
        } else if (id == R.id.nav_find_duplicates) {
            launchFindDuplicates();
        } else if (item.getIntent() != null) {
            ImplicitIntentsUtil.startActivityInApp(this, item.getIntent());
        } else {
            Log.w(TAG, "Unhandled navigation view item selection");
        }

        mDrawer.closeDrawer(GravityCompat.START);
        return true;
    }

    private Intent createPreferenceIntent() {
        final Intent intent = new Intent(this, ContactsPreferenceActivity.class);
        intent.putExtra(ContactsPreferenceActivity.EXTRA_NEW_LOCAL_PROFILE,
                ContactEditorFragment.INTENT_EXTRA_NEW_LOCAL_PROFILE);
        intent.putExtra(ContactsPreferenceActivity.EXTRA_MODE_FULLY_EXPANDED,
                QuickContactActivity.MODE_FULLY_EXPANDED);
        intent.putExtra(ContactsPreferenceActivity.EXTRA_PREVIOUS_SCREEN_TYPE,
                QuickContactActivity.EXTRA_PREVIOUS_SCREEN_TYPE);
        return intent;
    }

    protected void switchToAllContacts() {
        final Intent intent = new Intent();
        final ContactListFilter filter = createAllAccountsFilter();
        intent.putExtra(AccountFilterUtil.EXTRA_CONTACT_LIST_FILTER, filter);
        AccountFilterUtil.handleAccountFilterResult(
                mContactListFilterController, AppCompatActivity.RESULT_OK, intent);
    }

    protected void launchFindDuplicates() {
        ImplicitIntentsUtil.startActivityInAppIfPossible(this,
                Assistants.getDuplicatesActivityIntent(this));
    }

    protected ContactListFilter createAllAccountsFilter() {
        return ContactListFilter.createFilterWithType(ContactListFilter.FILTER_TYPE_ALL_ACCOUNTS);
    }

}
