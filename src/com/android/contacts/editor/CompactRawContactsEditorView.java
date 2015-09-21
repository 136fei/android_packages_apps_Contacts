/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.contacts.editor;

import com.android.contacts.R;
import com.android.contacts.common.model.AccountTypeManager;
import com.android.contacts.common.model.RawContactDelta;
import com.android.contacts.common.model.RawContactDeltaList;
import com.android.contacts.common.model.RawContactModifier;
import com.android.contacts.common.model.ValuesDelta;
import com.android.contacts.common.model.account.AccountType;
import com.android.contacts.common.model.account.AccountType.EditField;
import com.android.contacts.common.model.account.AccountWithDataSet;
import com.android.contacts.common.model.dataitem.DataKind;
import com.android.contacts.common.util.AccountsListAdapter;
import com.android.contacts.common.util.MaterialColorMapUtils;
import com.android.contacts.editor.CompactContactEditorFragment.PhotoHandler;
import com.android.contacts.util.UiClosables;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * View to display information from multiple {@link RawContactDelta}s grouped together
 * (e.g. all the phone numbers from a {@link com.android.contacts.common.model.Contact} together.
 */
public class CompactRawContactsEditorView extends LinearLayout implements View.OnClickListener {

    private static final String TAG = "CompactEditorView";

    /**
     * Callbacks for hosts of {@link CompactRawContactsEditorView}s.
     */
    public interface Listener {

        /**
         * Invoked when the compact editor should be expanded to show all fields.
         */
        public void onExpandEditor();

        /**
         * Invoked when the structured name editor field has changed.
         *
         * @param rawContactId The raw contact ID from the underlying {@link RawContactDelta}.
         * @param valuesDelta The values from the underlying {@link RawContactDelta}.
         */
        public void onNameFieldChanged(long rawContactId, ValuesDelta valuesDelta);

        /**
         * Invoked when the compact editor should rebind editors for a new account.
         *
         * @param oldState Old data being edited.
         * @param oldAccount Old account associated with oldState.
         * @param newAccount New account to be used.
         */
        public void onRebindEditorsForNewContact(RawContactDelta oldState,
                AccountWithDataSet oldAccount, AccountWithDataSet newAccount);
    }

    /**
     * Marks a name as super primary when it is changed.
     *
     * This is for the case when two or more raw contacts with names are joined where neither is
     * marked as super primary.  If the user hits back (which causes a save) after changing the
     * name that was arbitrarily displayed, we want that to be the name that is used.
     *
     * Should only be set when a super primary name does not already exist since we only show
     * one name field.
     */
    static final class NameEditorListener implements Editor.EditorListener {

        private final ValuesDelta mValuesDelta;
        private final long mRawContactId;
        private final Listener mListener;

        public NameEditorListener(ValuesDelta valuesDelta, long rawContactId,
                Listener listener) {
            mValuesDelta = valuesDelta;
            mRawContactId = rawContactId;
            mListener = listener;
        }

        @Override
        public void onRequest(int request) {
            if (request == Editor.EditorListener.FIELD_CHANGED) {
                mValuesDelta.setSuperPrimary(true);
                if (mListener != null) {
                    mListener.onNameFieldChanged(mRawContactId, mValuesDelta);
                }
            } else if (request == Editor.EditorListener.FIELD_TURNED_EMPTY) {
                mValuesDelta.setSuperPrimary(false);
            }
        }

        @Override
        public void onDeleteRequested(Editor editor) {
        }
    }

    private Listener mListener;

    private AccountTypeManager mAccountTypeManager;
    private LayoutInflater mLayoutInflater;

    private ViewIdGenerator mViewIdGenerator;
    private MaterialColorMapUtils.MaterialPalette mMaterialPalette;
    private long mPhotoId;
    private long mNameId;
    private String mReadOnlyDisplayName;
    private boolean mHasNewContact;
    private boolean mIsUserProfile;
    private AccountWithDataSet mPrimaryAccount;
    private RawContactDelta mPrimaryRawContactDelta;
    private Map<String,List<KindSectionData>> mKindSectionDataMap = new HashMap<>();

    // Account header
    private View mAccountHeaderContainer;
    private TextView mAccountHeaderType;
    private TextView mAccountHeaderName;

    // Account selector
    private View mAccountSelectorContainer;
    private View mAccountSelector;
    private TextView mAccountSelectorType;
    private TextView mAccountSelectorName;

    private CompactPhotoEditorView mPhoto;
    private ViewGroup mNames;
    private ViewGroup mPhoneticNames;
    private ViewGroup mNicknames;
    private ViewGroup mPhoneNumbers;
    private ViewGroup mEmails;
    private ViewGroup mOtherTypes;
    private Map<String,LinearLayout> mOtherTypesMap = new HashMap<>();
    private View mMoreFields;

    // The ValuesDelta for the non super primary name that was displayed to the user.
    private ValuesDelta mNameValuesDelta;

    private long mPhotoRawContactId;

    private boolean mUsingDefaultNameEditorView;
    private StructuredNameEditorView mDefaultNameEditorView;

    public CompactRawContactsEditorView(Context context) {
        super(context);
    }

    public CompactRawContactsEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Sets the receiver for {@link CompactRawContactsEditorView} callbacks.
     */
    public void setListener(Listener listener) {
        mListener = listener;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mAccountTypeManager = AccountTypeManager.getInstance(getContext());
        mLayoutInflater = (LayoutInflater)
                getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        // Account header
        mAccountHeaderContainer = findViewById(R.id.account_container);
        mAccountHeaderType = (TextView) findViewById(R.id.account_type);
        mAccountHeaderName = (TextView) findViewById(R.id.account_name);

        // Account selector
        mAccountSelectorContainer = findViewById(R.id.account_selector_container);
        mAccountSelector = findViewById(R.id.account);
        mAccountSelectorType = (TextView) findViewById(R.id.account_type_selector);
        mAccountSelectorName = (TextView) findViewById(R.id.account_name_selector);

        mPhoto = (CompactPhotoEditorView) findViewById(R.id.photo_editor);
        mNames = (LinearLayout) findViewById(R.id.names);
        mPhoneticNames = (LinearLayout) findViewById(R.id.phonetic_names);
        mNicknames = (LinearLayout) findViewById(R.id.nicknames);
        mPhoneNumbers = (LinearLayout) findViewById(R.id.phone_numbers);
        mEmails = (LinearLayout) findViewById(R.id.emails);
        mOtherTypes = (LinearLayout) findViewById(R.id.other);
        mMoreFields = findViewById(R.id.more_fields);
        mMoreFields.setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.more_fields && mListener != null ) {
            mListener.onExpandEditor();
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setEnabled(enabled, mNames);
        setEnabled(enabled, mPhoneticNames);
        setEnabled(enabled, mNicknames);
        setEnabled(enabled, mPhoneNumbers);
        setEnabled(enabled, mEmails);
        for (Map.Entry<String,LinearLayout> otherType : mOtherTypesMap.entrySet()) {
            setEnabled(enabled, otherType.getValue());
        }
    }

    private void setEnabled(boolean enabled, ViewGroup viewGroup) {
        if (viewGroup != null) {
            final int childCount = viewGroup.getChildCount();
            for (int i = 0; i < childCount; i++) {
                viewGroup.getChildAt(i).setEnabled(enabled);
            }
        }
    }

    /**
     * Pass through to {@link CompactPhotoEditorView#setPhotoHandler}.
     */
    public void setPhotoHandler(PhotoHandler photoHandler) {
        mPhoto.setPhotoHandler(photoHandler);
    }

    /**
     * Pass through to {@link CompactPhotoEditorView#setPhoto}.
     */
    public void setPhoto(Bitmap bitmap) {
        mPhoto.setPhoto(bitmap);
    }

    /**
     * Pass through to {@link CompactPhotoEditorView#setFullSizedPhoto(Uri)}.
     */
    public void setFullSizePhoto(Uri photoUri) {
        mPhoto.setFullSizedPhoto(photoUri);
    }

    /**
     * Pass through to {@link CompactPhotoEditorView#isWritablePhotoSet}.
     */
    public boolean isWritablePhotoSet() {
        return mPhoto.isWritablePhotoSet();
    }

    /**
     * Get the raw contact ID for the CompactHeaderView photo.
     */
    public long getPhotoRawContactId() {
        return mPhotoRawContactId;
    }

    public StructuredNameEditorView getDefaultNameEditorView() {
        return mDefaultNameEditorView;
    }

    public StructuredNameEditorView getStructuredNameEditorView() {
        // We only ever show one StructuredName
        return mNames.getChildCount() == 0
                ? null : (StructuredNameEditorView) mNames.getChildAt(0);
    }

    public PhoneticNameEditorView getFirstPhoneticNameEditorView() {
        // There should only ever be one phonetic name
        return mPhoneticNames.getChildCount() == 0
                ? null : (PhoneticNameEditorView) mPhoneticNames.getChildAt(0);
    }

    public View getAggregationAnchorView() {
        // Since there is only one structured name we can just return it as the anchor for
        // the aggregation suggestions popup
        if (mNames.getChildCount() == 0) {
            return null;
        }
        return mNames.getChildAt(0).findViewById(R.id.anchor_view);
    }

    /**
     * @param readOnlyDisplayName The display name to set on the new raw contact created in order
     *         to edit a read-only contact.
     */
    public void setState(RawContactDeltaList rawContactDeltas,
            MaterialColorMapUtils.MaterialPalette materialPalette, ViewIdGenerator viewIdGenerator,
            long photoId, long nameId, String readOnlyDisplayName, boolean hasNewContact,
            boolean isUserProfile, AccountWithDataSet primaryAccount) {
        mKindSectionDataMap.clear();

        mNames.removeAllViews();
        mPhoneticNames.removeAllViews();
        mNicknames.removeAllViews();
        mPhoneNumbers.removeAllViews();
        mEmails.removeAllViews();
        mOtherTypes.removeAllViews();
        mOtherTypesMap.clear();
        mMoreFields.setVisibility(View.VISIBLE);

        if (rawContactDeltas == null || rawContactDeltas.isEmpty()) {
            return;
        }

        mViewIdGenerator = viewIdGenerator;
        setId(mViewIdGenerator.getId(rawContactDeltas.get(0), /* dataKind =*/ null,
                /* valuesDelta =*/ null, ViewIdGenerator.NO_VIEW_INDEX));
        mMaterialPalette = materialPalette;
        mPhotoId = photoId;
        mNameId = nameId;
        mReadOnlyDisplayName = readOnlyDisplayName;
        mHasNewContact = hasNewContact;
        mIsUserProfile = isUserProfile;
        mPrimaryAccount = primaryAccount;
        if (mPrimaryAccount == null) {
            mPrimaryAccount = ContactEditorUtils.getInstance(getContext()).getDefaultAccount();
        }
        vlog("state: primary " + mPrimaryAccount);

        vlog("state: setting compact editor state from " + rawContactDeltas);
        parseRawContactDeltas(rawContactDeltas);
        addAccountInfo();
        addPhotoView();
        addStructuredNameView();
        addEditorViews(rawContactDeltas);
        updateKindEditorEmptyFields(mPhoneNumbers);
        updateKindEditorIcons(mPhoneNumbers);
        updateKindEditorEmptyFields(mEmails);
        updateKindEditorIcons(mEmails);
        for (Map.Entry<String,LinearLayout> otherTypes : mOtherTypesMap.entrySet()) {
            updateKindEditorIcons(otherTypes.getValue());
        }
    }

    private void parseRawContactDeltas(RawContactDeltaList rawContactDeltas) {
        // Get the raw contact delta for the primary account (the one displayed at the top)
        if (mPrimaryAccount == null || TextUtils.isEmpty(mPrimaryAccount.name)
                || !TextUtils.isEmpty(mReadOnlyDisplayName)) {
            // Use the first writable contact if this is an insert for a read-only contact.
            // In this case we can assume the first writable raw contact is the newly created one
            // because inserts have a raw contact delta list of size 1 and read-only contacts have
            // a list of size 2.
            for (RawContactDelta rawContactDelta : rawContactDeltas) {
                if (!rawContactDelta.isVisible()) continue;
                final AccountType accountType = rawContactDelta.getAccountType(mAccountTypeManager);
                if (accountType != null && accountType.areContactsWritable()) {
                    vlog("parse: using first writable raw contact as primary");
                    mPrimaryRawContactDelta = rawContactDelta;
                    break;
                }
            }
        } else {
            // Use the first writable contact that matches the primary account
            for (RawContactDelta rawContactDelta : rawContactDeltas) {
                if (!rawContactDelta.isVisible()) continue;
                final AccountType accountType = rawContactDelta.getAccountType(mAccountTypeManager);
                if (accountType != null && accountType.areContactsWritable()
                        && Objects.equals(mPrimaryAccount.name, rawContactDelta.getAccountName())
                        && Objects.equals(mPrimaryAccount.type, rawContactDelta.getAccountType())
                        && Objects.equals(mPrimaryAccount.dataSet, rawContactDelta.getDataSet())) {
                    vlog("parse: matched the primary account raw contact");
                    mPrimaryRawContactDelta = rawContactDelta;
                    break;
                }
            }
        }
        if (mPrimaryRawContactDelta == null) {
            // Fall back to the first writable raw contact
            for (RawContactDelta rawContactDelta : rawContactDeltas) {
                if (!rawContactDelta.isVisible()) continue;
                final AccountType accountType = rawContactDelta.getAccountType(mAccountTypeManager);
                if (accountType != null && accountType.areContactsWritable()) {
                    vlog("parse: falling back to the first writable raw contact as primary");
                    mPrimaryRawContactDelta = rawContactDelta;
                    break;
                }
            }
        }

        RawContactModifier.ensureKindExists(mPrimaryRawContactDelta,
                mPrimaryRawContactDelta.getAccountType(mAccountTypeManager),
                StructuredName.CONTENT_ITEM_TYPE);

        RawContactModifier.ensureKindExists(mPrimaryRawContactDelta,
                mPrimaryRawContactDelta.getAccountType(mAccountTypeManager),
                Photo.CONTENT_ITEM_TYPE);

        // Build the kind section data list map
        for (RawContactDelta rawContactDelta : rawContactDeltas) {
            if (rawContactDelta == null || !rawContactDelta.isVisible()) continue;
            final AccountType accountType = rawContactDelta.getAccountType(mAccountTypeManager);
            if (accountType == null) continue;
            final List<DataKind> dataKinds = accountType.getSortedDataKinds();
            final int dataKindSize = dataKinds == null ? 0 : dataKinds.size();
            vlog("parse: " + dataKindSize + " dataKinds(s)");
            for (int i = 0; i < dataKindSize; i++) {
                final DataKind dataKind = dataKinds.get(i);
                // Don't show read only values
                if (dataKind == null || !dataKind.editable) continue;
                // Get the kind section data list to add the new field to
                List<KindSectionData> kindSectionDataList =
                        mKindSectionDataMap.get(dataKind.mimeType);
                if (kindSectionDataList == null) {
                    kindSectionDataList = new ArrayList<>();
                    mKindSectionDataMap.put(dataKind.mimeType, kindSectionDataList);
                }
                final KindSectionData kindSectionData =
                        new KindSectionData(accountType, dataKind, rawContactDelta);
                kindSectionDataList.add(kindSectionData);
                vlog("parse: " + i + " " + dataKind.mimeType + " " +
                        (kindSectionData.hasValuesDeltas()
                                ? kindSectionData.getValuesDeltas().size() : null) + " value(s)");
            }
        }
    }

    private void addAccountInfo() {
        if (mPrimaryRawContactDelta == null) {
            mAccountHeaderContainer.setVisibility(View.GONE);
            mAccountSelectorContainer.setVisibility(View.GONE);
            return;
        }

        // Get the account information for the primary raw contact delta
        final Pair<String,String> accountInfo = EditorUiUtils.getAccountInfo(getContext(),
                mIsUserProfile, mPrimaryRawContactDelta.getAccountName(),
                mPrimaryRawContactDelta.getAccountType(mAccountTypeManager));

        // The account header and selector show the same information so both shouldn't be visible
        // at the same time
        final List<AccountWithDataSet> accounts =
                AccountTypeManager.getInstance(getContext()).getAccounts(true);
        if (mHasNewContact && !mIsUserProfile && accounts.size() > 1) {
            mAccountHeaderContainer.setVisibility(View.GONE);
            addAccountSelector(accountInfo);
        } else {
            addAccountHeader(accountInfo);
            mAccountSelectorContainer.setVisibility(View.GONE);
        }
    }

    private void addAccountHeader(Pair<String,String> accountInfo) {
        if (TextUtils.isEmpty(accountInfo.first)) {
            // Hide this view so the other text view will be centered vertically
            mAccountHeaderName.setVisibility(View.GONE);
        } else {
            mAccountHeaderName.setVisibility(View.VISIBLE);
            mAccountHeaderName.setText(accountInfo.first);
        }
        mAccountHeaderType.setText(accountInfo.second);

        mAccountHeaderContainer.setContentDescription(
                EditorUiUtils.getAccountInfoContentDescription(
                        accountInfo.first, accountInfo.second));
    }

    private void addAccountSelector(Pair<String,String> accountInfo) {
        mAccountSelectorContainer.setVisibility(View.VISIBLE);

        if (TextUtils.isEmpty(accountInfo.first)) {
            // Hide this view so the other text view will be centered vertically
            mAccountSelectorName.setVisibility(View.GONE);
        } else {
            mAccountSelectorName.setVisibility(View.VISIBLE);
            mAccountSelectorName.setText(accountInfo.first);
        }
        mAccountSelectorType.setText(accountInfo.second);

        mAccountSelectorContainer.setContentDescription(
                EditorUiUtils.getAccountInfoContentDescription(
                        accountInfo.first, accountInfo.second));

        mAccountSelector.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final ListPopupWindow popup = new ListPopupWindow(getContext(), null);
                final AccountsListAdapter adapter =
                        new AccountsListAdapter(getContext(),
                                AccountsListAdapter.AccountListFilter.ACCOUNTS_CONTACT_WRITABLE,
                                mPrimaryAccount);
                popup.setWidth(mAccountSelectorContainer.getWidth());
                popup.setAnchorView(mAccountSelectorContainer);
                popup.setAdapter(adapter);
                popup.setModal(true);
                popup.setInputMethodMode(ListPopupWindow.INPUT_METHOD_NOT_NEEDED);
                popup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View view, int position,
                            long id) {
                        UiClosables.closeQuietly(popup);
                        final AccountWithDataSet newAccount = adapter.getItem(position);
                        if (mListener != null && !mPrimaryAccount.equals(newAccount)) {
                            mListener.onRebindEditorsForNewContact(
                                    mPrimaryRawContactDelta,
                                    mPrimaryAccount,
                                    newAccount);
                        }
                    }
                });
                popup.show();
            }
        });
    }

    private void addPhotoView() {
        // Get the kind section data and values delta that will back the photo view
        final String mimeType = Photo.CONTENT_ITEM_TYPE;
        Pair<KindSectionData,ValuesDelta> pair = getPrimaryKindSectionData(mimeType, mPhotoId);
        if (pair == null) {
            wlog(mimeType + ": no kind section data parsed");
            return;
        }
        final KindSectionData kindSectionData = pair.first;
        final ValuesDelta valuesDelta = pair.second;

        // If we're editing a read-only contact we want to display the photo from the
        // read-only contact in a photo editor backed by the new raw contact
        // that was created. The new raw contact is the first writable one.
        // See go/editing-read-only-contacts
        if (mReadOnlyDisplayName != null) {
            mPhotoRawContactId = mPrimaryRawContactDelta.getRawContactId();
        }

        mPhotoRawContactId = kindSectionData.getRawContactDelta().getRawContactId();
        mPhoto.setValues(kindSectionData.getDataKind(), valuesDelta,
                kindSectionData.getRawContactDelta(),
                !kindSectionData.getAccountType().areContactsWritable(), mMaterialPalette,
                mViewIdGenerator);
    }

    private void addStructuredNameView() {
        // Get the kind section data and values delta that will back the name view
        final String mimeType = StructuredName.CONTENT_ITEM_TYPE;
        Pair<KindSectionData,ValuesDelta> pair = getPrimaryKindSectionData(mimeType, mNameId);
        if (pair == null) {
            wlog(mimeType + ": no kind section data parsed");
            return;
        }
        final KindSectionData kindSectionData = pair.first;
        final ValuesDelta valuesDelta = pair.second;

        // If we're editing a read-only contact we want to display the name from the
        // read-only contact in the name editor backed by the new raw contact
        // that was created. The new raw contact is the first writable one.
        // See go/editing-read-only-contacts
        // TODO
        if (!TextUtils.isEmpty(mReadOnlyDisplayName)) {
            for (KindSectionData data : mKindSectionDataMap.get(mimeType)) {
                if (data.getAccountType().areContactsWritable()) {
                    vlog(mimeType + ": using name from read-only contact");
                    mUsingDefaultNameEditorView = true;
                    break;
                }
            }
        }

        final NameEditorListener nameEditorListener = new NameEditorListener(valuesDelta,
                kindSectionData.getRawContactDelta().getRawContactId(), mListener);
        final StructuredNameEditorView nameEditorView = inflateStructuredNameEditorView(
                mNames, kindSectionData.getAccountType(), valuesDelta,
                kindSectionData.getRawContactDelta(), nameEditorListener,
                !kindSectionData.getAccountType().areContactsWritable());
        mNames.addView(nameEditorView);

        // TODO: Remove this after eliminating the full editor
        mNameValuesDelta = valuesDelta;
        if (mUsingDefaultNameEditorView) {
            mDefaultNameEditorView = nameEditorView;
        }
    }

    private Pair<KindSectionData,ValuesDelta> getPrimaryKindSectionData(String mimeType, long id) {
        final List<KindSectionData> kindSectionDataList = mKindSectionDataMap.get(mimeType);
        if (kindSectionDataList == null || kindSectionDataList.isEmpty()) {
            wlog(mimeType + ": no kind section data parsed");
            return null;
        }

        KindSectionData resultKindSectionData = null;
        ValuesDelta resultValuesDelta = null;
        if (id > 0) {
            // Look for a match for the ID that was passed in
            for (KindSectionData kindSectionData : kindSectionDataList) {
                resultValuesDelta = kindSectionData.getValuesDeltaById(id);
                if (resultValuesDelta != null) {
                    vlog(mimeType + ": matched kind section data by ID");
                    resultKindSectionData = kindSectionData;
                    break;
                }
            }
        }
        if (resultKindSectionData == null) {
            // Look for a super primary photo
            for (KindSectionData kindSectionData : kindSectionDataList) {
                resultValuesDelta = kindSectionData.getSuperPrimaryValuesDelta();
                if (resultValuesDelta != null) {
                    wlog(mimeType + ": matched super primary kind section data");
                    resultKindSectionData = kindSectionData;
                    break;
                }
            }
        }
        if (resultKindSectionData == null) {
            // Fall back to the first non-empty value
            for (KindSectionData kindSectionData : kindSectionDataList) {
                resultValuesDelta = kindSectionData.getFirstNonEmptyValuesDelta();
                if (resultValuesDelta != null) {
                    vlog(mimeType + ": using first non empty value");
                    resultKindSectionData = kindSectionData;
                    break;
                }
            }
        }
        if (resultKindSectionData == null || resultValuesDelta == null) {
            final List<ValuesDelta> valuesDeltaList = kindSectionDataList.get(0).getValuesDeltas();
            if (valuesDeltaList != null && !valuesDeltaList.isEmpty()) {
                vlog(mimeType + ": falling back to first empty entry");
                resultValuesDelta = valuesDeltaList.get(0);
                resultKindSectionData = kindSectionDataList.get(0);
            }
        }
        return resultKindSectionData != null && resultValuesDelta != null
                ? new Pair<>(resultKindSectionData, resultValuesDelta) : null;
    }

    private void addEditorViews(RawContactDeltaList rawContactDeltas) {
        for (RawContactDelta rawContactDelta : rawContactDeltas) {
            if (!rawContactDelta.isVisible()) continue;
            final AccountType accountType = rawContactDelta.getAccountType(mAccountTypeManager);

            for (DataKind dataKind : accountType.getSortedDataKinds()) {
                if (!dataKind.editable) continue;

                final String mimeType = dataKind.mimeType;
                vlog(mimeType + " " + dataKind.fieldList.size() + " field(s)");
                if (Photo.CONTENT_ITEM_TYPE.equals(mimeType)
                        || StructuredName.CONTENT_ITEM_TYPE.equals(mimeType)
                        || GroupMembership.CONTENT_ITEM_TYPE.equals(mimeType)) {
                    // Photos and structured names are handled separately and
                    // group membership is not supported
                    continue;
                } else if (DataKind.PSEUDO_MIME_TYPE_PHONETIC_NAME.equals(mimeType)) {
                    // Only add phonetic names if there is a non-empty one. Note the use of
                    // StructuredName mimeType below, even though we matched a pseudo mime type.
                    final ValuesDelta valuesDelta = rawContactDelta.getSuperPrimaryEntry(
                            StructuredName.CONTENT_ITEM_TYPE, /* forceSelection =*/ true);
                    if (hasNonEmptyValue(dataKind, valuesDelta)) {
                        mPhoneticNames.addView(inflatePhoneticNameEditorView(
                                mPhoneticNames, accountType, valuesDelta, rawContactDelta));
                    }
                } else if (Nickname.CONTENT_ITEM_TYPE.equals(mimeType)) {
                    // Add all non-empty nicknames
                    final List<ValuesDelta> valuesDeltas = getNonEmptyValuesDeltas(
                            rawContactDelta, Nickname.CONTENT_ITEM_TYPE, dataKind);
                    if (valuesDeltas != null && !valuesDeltas.isEmpty()) {
                        for (ValuesDelta valuesDelta : valuesDeltas) {
                            mNicknames.addView(inflateNicknameEditorView(
                                    mNicknames, dataKind, valuesDelta, rawContactDelta));
                        }
                    }
                } else if (Phone.CONTENT_ITEM_TYPE.equals(mimeType)) {
                    final KindSectionView kindSectionView =
                            inflateKindSectionView(mPhoneNumbers, dataKind, rawContactDelta);
                    kindSectionView.setListener(new KindSectionView.Listener() {
                        @Override
                        public void onDeleteRequested(Editor editor) {
                            if (kindSectionView.getEditorCount() == 1) {
                                kindSectionView.markForRemoval();
                                EditorAnimator.getInstance().removeEditorView(kindSectionView);
                            } else {
                                editor.deleteEditor();
                            }
                            updateKindEditorEmptyFields(mPhoneNumbers);
                            updateKindEditorIcons(mPhoneNumbers);
                        }
                    });
                    mPhoneNumbers.addView(kindSectionView);
                } else if (Email.CONTENT_ITEM_TYPE.equals(mimeType)) {
                    final KindSectionView kindSectionView =
                            inflateKindSectionView(mEmails, dataKind, rawContactDelta);
                    kindSectionView.setListener(new KindSectionView.Listener() {
                        @Override
                        public void onDeleteRequested(Editor editor) {
                            if (kindSectionView.getEditorCount() == 1) {
                                kindSectionView.markForRemoval();
                                EditorAnimator.getInstance().removeEditorView(kindSectionView);
                            } else {
                                editor.deleteEditor();
                            }
                            updateKindEditorEmptyFields(mEmails);
                            updateKindEditorIcons(mEmails);
                        }
                    });
                    mEmails.addView(kindSectionView);
                } else if (hasNonEmptyValuesDelta(rawContactDelta, mimeType, dataKind)) {
                    final LinearLayout otherTypeViewGroup;
                    if (mOtherTypesMap.containsKey(mimeType)) {
                        otherTypeViewGroup = mOtherTypesMap.get(mimeType);
                    } else {
                        otherTypeViewGroup = new LinearLayout(getContext());
                        otherTypeViewGroup.setOrientation(LinearLayout.VERTICAL);
                        mOtherTypes.addView(otherTypeViewGroup);
                        mOtherTypesMap.put(mimeType, otherTypeViewGroup);
                    }
                    final KindSectionView kindSectionView =
                            inflateKindSectionView(mOtherTypes, dataKind, rawContactDelta);
                    kindSectionView.setListener(new KindSectionView.Listener() {
                        @Override
                        public void onDeleteRequested(Editor editor) {
                            if (kindSectionView.getEditorCount() == 1) {
                                kindSectionView.markForRemoval();
                                EditorAnimator.getInstance().removeEditorView(kindSectionView);
                            } else {
                                editor.deleteEditor();
                            }
                            updateKindEditorIcons(otherTypeViewGroup);
                        }
                    });
                    otherTypeViewGroup.addView(kindSectionView);
                }
            }
        }
    }

    private static void updateKindEditorEmptyFields(ViewGroup viewGroup) {
        KindSectionView lastVisibleKindSectionView = null;
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            if (viewGroup.getChildAt(i).getVisibility() == View.VISIBLE) {
                lastVisibleKindSectionView = (KindSectionView) viewGroup.getChildAt(i);
            }
        }
        // Only the last editor should show an empty editor
        if (lastVisibleKindSectionView != null) {
            // Hide all empty kind sections except the last one
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                final KindSectionView kindSectionView = (KindSectionView) viewGroup.getChildAt(i);
                if (kindSectionView != lastVisibleKindSectionView
                        && kindSectionView.areAllEditorsEmpty()) {
                    kindSectionView.setVisibility(View.GONE);
                }
            }
            // Set the last editor to show empty editor fields
            lastVisibleKindSectionView.setShowOneEmptyEditor(true);
            lastVisibleKindSectionView.updateEmptyEditors(/* shouldAnimate =*/ false);
        }
    }

    private static void updateKindEditorIcons(ViewGroup viewGroup) {
        // Show the icon on the first visible kind editor
        boolean iconVisible = false;
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            final KindSectionView kindSectionView = (KindSectionView) viewGroup.getChildAt(i);
            if (kindSectionView.getVisibility() != View.VISIBLE
                    || kindSectionView.isMarkedForRemoval()) {
                continue;
            }
            if (!iconVisible) {
                kindSectionView.setIconVisibility(true);
                iconVisible = true;
            } else {
                kindSectionView.setIconVisibility(false);
            }
        }
    }

    private static boolean hasNonEmptyValuesDelta(RawContactDelta rawContactDelta,
            String mimeType, DataKind dataKind) {
        return !getNonEmptyValuesDeltas(rawContactDelta, mimeType, dataKind).isEmpty();
    }

    private static ValuesDelta getNonEmptySuperPrimaryValuesDeltas(RawContactDelta rawContactDelta,
            String mimeType, DataKind dataKind) {
        for (ValuesDelta valuesDelta : getNonEmptyValuesDeltas(
                rawContactDelta, mimeType, dataKind)) {
            if (valuesDelta.isSuperPrimary()) {
                return valuesDelta;
            }
        }
        return null;
    }

    static List<ValuesDelta> getNonEmptyValuesDeltas(RawContactDelta rawContactDelta,
            String mimeType, DataKind dataKind) {
        final List<ValuesDelta> result = new ArrayList<>();
        if (rawContactDelta == null) {
            vlog("Null RawContactDelta");
            return result;
        }
        if (!rawContactDelta.hasMimeEntries(mimeType)) {
            vlog("No ValueDeltas");
            return result;
        }
        for (ValuesDelta valuesDelta : rawContactDelta.getMimeEntries(mimeType)) {
            if (hasNonEmptyValue(dataKind, valuesDelta)) {
                result.add(valuesDelta);
            }
        }
        return result;
    }

    private static boolean hasNonEmptyValue(DataKind dataKind, ValuesDelta valuesDelta) {
        if (valuesDelta == null) {
            vlog("Null valuesDelta");
            return false;
        }
        for (EditField editField : dataKind.fieldList) {
            final String column = editField.column;
            final String value = valuesDelta == null ? null : valuesDelta.getAsString(column);
            vlog("Field " + column + " empty=" + TextUtils.isEmpty(value) + " value=" + value);
            if (!TextUtils.isEmpty(value)) {
                return true;
            }
        }
        return false;
    }

    private StructuredNameEditorView inflateStructuredNameEditorView(ViewGroup viewGroup,
            AccountType accountType, ValuesDelta valuesDelta, RawContactDelta rawContactDelta,
            NameEditorListener nameEditorListener, boolean readOnly) {
        final StructuredNameEditorView result = (StructuredNameEditorView) mLayoutInflater.inflate(
                R.layout.structured_name_editor_view, viewGroup, /* attachToRoot =*/ false);
        if (nameEditorListener != null) {
            result.setEditorListener(nameEditorListener);
        }
        result.setDeletable(false);
        result.setValues(
                accountType.getKindForMimetype(DataKind.PSEUDO_MIME_TYPE_DISPLAY_NAME),
                valuesDelta,
                rawContactDelta,
                readOnly,
                mViewIdGenerator);
        return result;
    }

    private PhoneticNameEditorView inflatePhoneticNameEditorView(ViewGroup viewGroup,
            AccountType accountType, ValuesDelta valuesDelta, RawContactDelta rawContactDelta) {
        final PhoneticNameEditorView result = (PhoneticNameEditorView) mLayoutInflater.inflate(
                R.layout.phonetic_name_editor_view, viewGroup, /* attachToRoot =*/ false);
        result.setDeletable(false);
        result.setValues(
                accountType.getKindForMimetype(DataKind.PSEUDO_MIME_TYPE_PHONETIC_NAME),
                valuesDelta,
                rawContactDelta,
                /* readOnly =*/ false,
                mViewIdGenerator);
        return result;
    }

    private TextFieldsEditorView inflateNicknameEditorView(ViewGroup viewGroup, DataKind dataKind,
            ValuesDelta valuesDelta, RawContactDelta rawContactDelta) {
        final TextFieldsEditorView result = (TextFieldsEditorView) mLayoutInflater.inflate(
                R.layout.nick_name_editor_view, viewGroup, /* attachToRoot =*/ false);
        result.setDeletable(false);
        result.setValues(
                dataKind,
                valuesDelta,
                rawContactDelta,
                /* readOnly =*/ false,
                mViewIdGenerator);
        return result;
    }


    private KindSectionView inflateKindSectionView(ViewGroup viewGroup, DataKind dataKind,
            RawContactDelta rawContactDelta) {
        final KindSectionView result = (KindSectionView) mLayoutInflater.inflate(
                R.layout.item_kind_section, viewGroup, /* attachToRoot =*/ false);
        result.setState(
                dataKind,
                rawContactDelta,
                /* readOnly =*/ false,
                mViewIdGenerator);
        return result;
    }

    private static void vlog(String message) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, message);
        }
    }

    private static void wlog(String message) {
        if (Log.isLoggable(TAG, Log.WARN)) {
            Log.w(TAG, message);
        }
    }

    private static void elog(String message) {
        Log.e(TAG, message);
    }
}
