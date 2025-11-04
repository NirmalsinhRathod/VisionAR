import Foundation
import UIKit

@objc(NativeARTextViewManager)
class NativeARTextViewManager: RCTViewManager {
  
  override func view() -> UIView! {
    return ARTextView()
  }
  
  override static func requiresMainQueueSetup() -> Bool {
    return true
  }
}

