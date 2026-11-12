# Build environment for the Cyperio Android app (same toolchain as upstream v2rayNG CI).
FROM eclipse-temurin:21-jdk
RUN apt-get update && apt-get install -y --no-install-recommends unzip curl git make file rsync \
    && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android NDK_VERSION=29.0.14206865
ENV NDK_HOME=$ANDROID_HOME/ndk/$NDK_VERSION
RUN mkdir -p $ANDROID_HOME/cmdline-tools && cd /tmp \
    && curl -fsSL -o ct.zip https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip \
    && unzip -q ct.zip && mv cmdline-tools $ANDROID_HOME/cmdline-tools/latest && rm ct.zip
RUN yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null \
    && $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --channel=0 \
       "platforms;android-37.0" "build-tools;37.0.0" "platform-tools" "ndk;$NDK_VERSION" >/dev/null
COPY inside.sh /usr/local/bin/inside.sh
RUN chmod 755 /usr/local/bin/inside.sh
ENTRYPOINT ["/usr/local/bin/inside.sh"]
