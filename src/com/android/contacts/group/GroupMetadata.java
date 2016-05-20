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
 * limitations under the License
 */
package com.android.contacts.group;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/** Meta data for a contact group. */
// TODO(wjang): consolidate with com.android.contacts.common.GroupMetaData;
public final class GroupMetadata implements Parcelable {

    public static final Creator<GroupMetadata> CREATOR = new Creator<GroupMetadata>() {

        public GroupMetadata createFromParcel(Parcel in) {
            return new GroupMetadata(in);
        }

        public GroupMetadata[] newArray(int size) {
            return new GroupMetadata[size];
        }
    };

    // TODO(wjang): make them all final and add getters
    public Uri uri;
    public String accountName;
    public String accountType;
    public String dataSet;
    public long groupId;
    public String groupName;
    public boolean readOnly;
    public boolean editable;
    public int memberCount = -1;

    public GroupMetadata() {
    }

    public GroupMetadata(Parcel source) {
        readFromParcel(source);
    }

    private void readFromParcel(Parcel source) {
        uri = source.readParcelable(Uri.class.getClassLoader());
        accountName = source.readString();
        accountType = source.readString();
        dataSet = source.readString();
        groupId = source.readLong();
        groupName = source.readString();
        readOnly = source.readInt() == 1;
        editable = source.readInt() == 1;
        memberCount = source.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(uri, 0);
        dest.writeString(accountName);
        dest.writeString(accountType);
        dest.writeString(dataSet);
        dest.writeLong(groupId);
        dest.writeString(groupName);
        dest.writeInt(readOnly ? 1 : 0);
        dest.writeInt(editable ? 1 : 0);
        dest.writeInt(memberCount);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "GroupMetadata[uri=" + uri +
                " accountName=" + accountName +
                " accountType=" + accountType +
                " dataSet=" + dataSet +
                " groupId=" + groupId +
                " groupName=" + groupName +
                " readOnly=" + readOnly +
                " editable=" + editable +
                " memberCount=" + memberCount +
                "]";
    }
}