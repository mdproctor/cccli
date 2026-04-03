# mac-ui-bridge/Makefile
DYLIB     = libMyMacUI.dylib
BUILD_DIR = build
SRC       = src/MyMacUI.m
INCLUDE   = include

.PHONY: all clean

all: $(BUILD_DIR)/$(DYLIB)

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

$(BUILD_DIR)/$(DYLIB): $(SRC) $(INCLUDE)/MyMacUI.h | $(BUILD_DIR)
	clang -dynamiclib \
	      -fobjc-arc \
	      -framework Cocoa \
	      -I$(INCLUDE) \
	      -install_name @rpath/$(DYLIB) \
	      -target arm64-apple-macos13.0 \
	      -o $@ $<

clean:
	rm -rf $(BUILD_DIR)
