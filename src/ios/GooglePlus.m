#import <GoogleOpenSource/GoogleOpenSource.h>
#import "AppDelegate.h"
#import "GooglePlus.h"

// need to swap out a method, so swizzling it here
static void swizzleMethod(Class class, SEL destinationSelector, SEL sourceSelector);

@implementation AppDelegate (IdentityUrlHandling)

+ (void)load {
  swizzleMethod([AppDelegate class],
                @selector(application:openURL:sourceApplication:annotation:),
                @selector(identity_application:openURL:sourceApplication:annotation:));
}

- (BOOL)identity_application: (UIApplication *)application
                     openURL: (NSURL *)url
           sourceApplication: (NSString *)sourceApplication
                  annotation: (id)annotation {

  GooglePlus* gp = (GooglePlus*)[[self.viewController pluginObjects] objectForKey:@"GooglePlus"];
  
  if ([gp isSigningIn]) {
    gp.isSigningIn = NO;
    return [GPPURLHandler handleURL:url sourceApplication:sourceApplication annotation:annotation];
  } else {
    // call super
    return [self identity_application:application openURL:url sourceApplication:sourceApplication annotation:annotation];
  }
}
@end

@implementation GooglePlus

- (void) login:(CDVInvokedUrlCommand*)command {
  self.isSigningIn = YES;
  [[self getGooglePlusSignInObject:command] authenticate];
}

- (void) trySilentLogin:(CDVInvokedUrlCommand*)command {
  // trySilentAuthentication doesn't call delegate when it fails, so handle it here
  if (![[self getGooglePlusSignInObject:command] trySilentAuthentication]) {
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"no valid token"];
    [self writeJavascript:[pluginResult toErrorCallbackString:command.callbackId]];
  }
}

- (GPPSignIn*) getGooglePlusSignInObject:(CDVInvokedUrlCommand*)command {
  _callbackId = command.callbackId;
  NSDictionary* options = [command.arguments objectAtIndex:0];
  NSString* apiKey = [options objectForKey:@"iOSApiKey"];
  if (apiKey == nil) {
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"iOSApiKey not set"];
    [self writeJavascript:[pluginResult toErrorCallbackString:_callbackId]];
    return nil;
  }

  NSString* webKey = [options objectForKey:@"webApiKey"];

  GPPSignIn *signIn = [GPPSignIn sharedInstance];
  signIn.shouldFetchGooglePlusUser = YES;
  signIn.shouldFetchGoogleUserEmail = YES;
  signIn.shouldFetchGoogleUserID = YES;
  signIn.clientID = apiKey;

  if (webKey != nil) {
    signIn.homeServerClientID = webKey;
  }

  signIn.scopes = [NSArray arrayWithObjects:
                             kGTLAuthScopePlusLogin,
                           @"openid",
                           @"email",
                           @"profile"
                           nil];

  signIn.attemptSSO = YES; // tries to use other installed Google apps
  signIn.delegate = self;
  return signIn;
}

- (void) logout:(CDVInvokedUrlCommand*)command {
  [[GPPSignIn sharedInstance] signOut];
  CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"logged out"];
  [self writeJavascript:[pluginResult toSuccessCallbackString:command.callbackId]];
}

- (void) disconnect:(CDVInvokedUrlCommand*)command {
  [[GPPSignIn sharedInstance] disconnect];
  CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"disconnected"];
  [self writeJavascript:[pluginResult toSuccessCallbackString:command.callbackId]];
}

- (void) share:(CDVInvokedUrlCommand*)command {
  id<GPPNativeShareBuilder> shareBuilder = [[GPPShare sharedInstance] nativeShareDialog];

  [[GPPShare sharedInstance] setDelegate:self];

  NSDictionary* options = [command.arguments objectAtIndex:0];
  // This line will fill out the title, description, and thumbnail from
  // the URL that you are sharing and includes a link to that URL.
  [shareBuilder setURLToShare:[NSURL URLWithString:[options objectForKey:@"url"]]];
  [shareBuilder open];
}

- (void)finishedSharingWithError:(NSError *)error {

  if (error) {
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error.localizedDescription];
    [self writeJavascript:[pluginResult toErrorCallbackString:_callbackId]];
  } else {
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self writeJavascript:[pluginResult toSuccessCallbackString:_callbackId]];
  }
}


#pragma mark - GPPSignInDelegate
- (void)finishedWithAuth:(GTMOAuth2Authentication *)auth
                   error:(NSError *)error {
  if (error) {
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error.localizedDescription];
    [self writeJavascript:[pluginResult toErrorCallbackString:_callbackId]];
  } else {
    NSString *email = [GPPSignIn sharedInstance].userEmail;
    NSString *token = [GPPSignIn sharedInstance].idToken;
    NSString *userId = [GPPSignIn sharedInstance].userID;
    GTLPlusPerson *person = [GPPSignIn sharedInstance].googlePlusUser;
    NSDictionary *result;
    
    if (person == nil) {
      result = @{
                 @"email" : email
                 };
    } else {
      result = @{
                 @"email"       : email,
                 @"idToken"     : token,
                 @"userId"      : userId,
                 @"displayName" : person.displayName ?: [NSNull null],
                 @"gender"      : person.gender ?: [NSNull null],
                 @"imageUrl"    : (person.image != nil && person.image.url != nil) ? person.image.url : [NSNull null],
                 @"givenName"   : (person.name != nil && person.name.givenName != nil) ? person.name.givenName : [NSNull null],
                 @"middleName"  : (person.name != nil && person.name.middleName != nil) ? person.name.middleName : [NSNull null],
                 @"familyName"  : (person.name != nil && person.name.familyName != nil) ? person.name.familyName : [NSNull null],
                 @"ageRangeMin" : person.ageRange && person.ageRange.min ? person.ageRange.min : [NSNull null],
                 @"ageRangeMax" : person.ageRange && person.ageRange.max ? person.ageRange.max : [NSNull null],
                 @"birthday"    : person.birthday ?: [NSNull null]
                 };
    }
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:result];
    [self writeJavascript:[pluginResult toSuccessCallbackString:_callbackId]];
  }
}

#pragma mark Swizzling

@end

static void swizzleMethod(Class class, SEL destinationSelector, SEL sourceSelector) {
  Method destinationMethod = class_getInstanceMethod(class, destinationSelector);
  Method sourceMethod = class_getInstanceMethod(class, sourceSelector);
  
  // If the method doesn't exist, add it.  If it does exist, replace it with the given implementation.
  if (class_addMethod(class, destinationSelector, method_getImplementation(sourceMethod), method_getTypeEncoding(sourceMethod))) {
    class_replaceMethod(class, destinationSelector, method_getImplementation(destinationMethod), method_getTypeEncoding(destinationMethod));
  } else {
    method_exchangeImplementations(destinationMethod, sourceMethod);
  }
}
