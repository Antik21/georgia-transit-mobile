#!/bin/sh
set -eu

source_config="$SRCROOT/Configuration/Firebase/$CONFIGURATION/GoogleService-Info.plist"
bundled_config="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/GoogleService-Info.plist"

if ! bundle_id=$(/usr/libexec/PlistBuddy -c 'Print :BUNDLE_ID' "$source_config") ||
   ! app_id=$(/usr/libexec/PlistBuddy -c 'Print :GOOGLE_APP_ID' "$source_config") ||
   [ "$bundle_id" != "$PRODUCT_BUNDLE_IDENTIFIER" ] ||
   [ -z "$app_id" ]; then
    echo "error: $source_config must identify the Firebase app for $PRODUCT_BUNDLE_IDENTIFIER."
    exit 1
fi

cp "$source_config" "$bundled_config"

# Upload the full bundle so Xcode's Sandbox debug dylib is symbolicated as well.
uploader="${BUILD_DIR%/Build/*}/SourcePackages/checkouts/firebase-ios-sdk/Crashlytics/upload-symbols"
dsym="$DWARF_DSYM_FOLDER_PATH/$DWARF_DSYM_FILE_NAME"
if ! "$uploader" -gsp "$bundled_config" -p ios "$dsym"; then
    echo "warning: Crashlytics dSYM upload failed for $CONFIGURATION."
fi
