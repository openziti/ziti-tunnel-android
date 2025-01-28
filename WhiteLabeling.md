White Labeling Ziti Mobile Edge for Android
=====
This projects allows to easily create a custom release (white-labeled app).

To create a custom build and release of ZME you need to do the following:
1. create `whitelabel.properties` file in the root of the project
2. define the following properties:
   |property| notes |
   |--------|-------|
   | `id`   | application identifier, must be unique if published |
   | `org`  | local variant identifier, used to configure the build tasks and artifacts. cannot contain `.`, `/`, or spaces |
   | `resourceDir` | (optional) location of resource overrides |

3. the build script will configure appropriate tasks and outputs:
   for example, if `org = myorg`, then `:ziti-mobile-edge:bundleDebug` tasks will create the white labeled bundle in
   `app/build/outputs/bundle/myorgDebug/ziti-mobile-edge-myorg-debug.aab`
4. white-labeled release artifacts need to be signed. the signing is configured via environment variables:
   - `RELEASE_KEYSTORE` - keystore file containing the signing key
   - `RELEASE_KEYSTORE_PASSWORD` - keystore password
   - `RELEASE_KEY_ALIAS` - alias/name of the signing key
   - `RELEASE_KEY_PASSWORD` - signing key password
