# Android GUI for [AmneziaWG](https://amnezia.org/learn-more/31_amneziawg)

**[Download from the Play Store](https://play.google.com/store/apps/details?id=org.amnezia.awg)**

This is an Android GUI for [AmneziaWG](https://amnezia.org/learn-more/31_amneziawg).

Library on Maven Central: [amenziawg-android](https://central.sonatype.com/artifact/com.zaneschepke/amneziawg-android)

## Use the amneziawg-android library

settings.gradle.kts
```
repositories {
  mavenCentral()
}
```

build.gradle.kts
```
dependencies {
    implementation("com.zaneschepke:amneziawg-android:1.2.0")
}
```

## Building

```
$ git clone --recurse-submodules https://github.com/amnezia-vpn/amneziawg-android
$ cd amneziawg-android
$ ./gradlew assembleRelease
```

macOS users may need [flock(1)](https://github.com/discoteq/flock).

## Publish

```sh
 ./gradlew publishAllPublicationsToCentralPortal  
```
