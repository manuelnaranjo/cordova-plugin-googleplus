#import <Cordova/CDVPlugin.h>
#import <GooglePlus/GooglePlus.h>
#import <GoogleOpenSource/GoogleOpenSource.h>

@interface GooglePlus : CDVPlugin<GPPSignInDelegate, GPPShareDelegate>

@property (nonatomic, copy) NSString* callbackId;
@property (nonatomic, assign) BOOL isSigningIn;

- (void) login:(CDVInvokedUrlCommand*)command;
- (void) trySilentLogin:(CDVInvokedUrlCommand*)command;
- (void) logout:(CDVInvokedUrlCommand*)command;
- (void) disconnect:(CDVInvokedUrlCommand*)command;
- (void) share:(CDVInvokedUrlCommand*)command;

@end
