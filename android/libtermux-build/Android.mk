LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := termux
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
LOCAL_CFLAGS := -Wall -Wextra -O2 -fPIC
LOCAL_SRC_FILES := \
    termux.c \
    termuxexec.c \
    termuxpty.c \
    termuxfile.c \
    termuxutil.c \
    termux-signal.c

LOCAL_LDLIBS := -llog -landroid

include $(BUILD_SHARED_LIBRARY)
