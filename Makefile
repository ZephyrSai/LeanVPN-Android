# LeanVPN for Android: builds with the SDK build-tools and a JDK; no Gradle, no Android Studio.
SDK       ?= $(HOME)/Library/Android/sdk
BT        ?= $(SDK)/build-tools/35.0.0
ANDROID   ?= $(SDK)/platforms/android-35/android.jar
JAVA_HOME ?= $(shell /usr/libexec/java_home 2>/dev/null || echo /opt/homebrew/opt/openjdk@21)
KEYSTORE  ?= $(HOME)/.android/leanvpn.keystore
KS_PASS   ?= android
ABI       ?= arm64-v8a
VERSION   := 0.1.0
CODE      := 1

# WireGuard's Android library (GoBackend + wireguard-go), pinned and checked.
WG_VER    := 1.0.20260102
WG_SHA256 := 2b9c16db026496123e4db695d26d03d1958a201096c7c4c89b21077dc70f3119
WG_URL    := https://repo1.maven.org/maven2/com/wireguard/android/tunnel/$(WG_VER)/tunnel-$(WG_VER).aar

export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)

OUT  := build
APK  := $(OUT)/LeanVPN.apk
SRC  := $(shell find src -name '*.java')
RES  := $(shell find res -type f)
WG   := $(OUT)/wg

all: $(APK)

$(WG)/classes.jar:
	mkdir -p $(WG)
	curl -fsSL -o $(OUT)/tunnel.aar $(WG_URL)
	echo "$(WG_SHA256)  $(OUT)/tunnel.aar" | shasum -a 256 -c -
	cd $(WG) && unzip -oq ../tunnel.aar classes.jar 'jni/*/libwg-go.so'

$(OUT)/res.zip: $(RES)
	mkdir -p $(OUT)
	$(BT)/aapt2 compile --dir res -o $@

# Resources + manifest; also generates R.java.
$(OUT)/base.apk: AndroidManifest.xml $(OUT)/res.zip
	mkdir -p $(OUT)/gen
	$(BT)/aapt2 link -I $(ANDROID) --manifest AndroidManifest.xml --java $(OUT)/gen \
		--min-sdk-version 29 --target-sdk-version 35 --version-code $(CODE) --version-name $(VERSION) \
		-0 arsc -o $@ $(OUT)/res.zip

$(OUT)/classes.dex: $(SRC) $(OUT)/base.apk $(WG)/classes.jar
	rm -rf $(OUT)/classes && mkdir -p $(OUT)/classes
	javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath $(ANDROID):$(BT)/core-lambda-stubs.jar \
		-cp $(WG)/classes.jar -d $(OUT)/classes $(SRC) $(shell find $(OUT)/gen -name '*.java')
	jar cf $(OUT)/app.jar -C $(OUT)/classes .
	$(BT)/d8 --release --min-api 29 --lib $(ANDROID) --output $(OUT) $(OUT)/app.jar $(WG)/classes.jar

$(KEYSTORE):
	mkdir -p $(dir $@)
	keytool -genkeypair -keystore $@ -storepass $(KS_PASS) -keypass $(KS_PASS) -alias leanvpn \
		-keyalg EC -groupname secp256r1 -validity 10000 -dname "CN=LeanVPN" -noprompt

# Native library stored uncompressed and 16 KB-aligned: mapped in place, never extracted.
$(APK): $(OUT)/base.apk $(OUT)/classes.dex $(KEYSTORE)
	rm -rf $(OUT)/pkg && mkdir -p $(OUT)/pkg/lib/$(ABI)
	cp $(OUT)/base.apk $(OUT)/unaligned.apk
	cp $(OUT)/classes.dex $(OUT)/pkg/
	cp $(WG)/jni/$(ABI)/libwg-go.so $(OUT)/pkg/lib/$(ABI)/
	cd $(OUT)/pkg && zip -qX ../unaligned.apk classes.dex && zip -qX0 ../unaligned.apk lib/$(ABI)/libwg-go.so
	$(BT)/zipalign -f -P 16 4 $(OUT)/unaligned.apk $(OUT)/aligned.apk
	$(BT)/apksigner sign --ks $(KEYSTORE) --ks-pass pass:$(KS_PASS) --ks-key-alias leanvpn --out $@ $(OUT)/aligned.apk
	@ls -l $@ | awk '{print "APK:", $$5, "bytes"}'

install: $(APK)
	adb install -r $(APK)

clean:
	rm -rf $(OUT)

.PHONY: all install clean
