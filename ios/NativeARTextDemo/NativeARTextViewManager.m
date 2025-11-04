#import <React/RCTViewManager.h>
#import <React/RCTEventEmitter.h>

@interface RCT_EXTERN_MODULE(NativeARTextViewManager, RCTViewManager)

// Expose the text property to React Native
RCT_EXPORT_VIEW_PROPERTY(text, NSString)

@end

