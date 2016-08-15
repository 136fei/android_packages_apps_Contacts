LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests
LOCAL_CERTIFICATE := shared

LOCAL_STATIC_JAVA_LIBRARIES := android-support-test

LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR := $(addprefix $(LOCAL_PATH)/, res)

LOCAL_PACKAGE_NAME := ContactsTests

LOCAL_INSTRUMENTATION_FOR := Contacts

LOCAL_SDK_VERSION := current
LOCAL_MIN_SDK_VERSION := 21

LOCAL_STATIC_JAVA_LIBRARIES := \
    mockito-target

LOCAL_AAPT_FLAGS := \
    --auto-add-overlay \
    --extra-packages com.android.contacts.common.tests

include $(BUILD_PACKAGE)
