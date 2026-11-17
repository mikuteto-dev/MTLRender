use objc2::rc::Retained;
use objc2::runtime::{NSObjectProtocol, ProtocolObject};
use objc2_foundation::{NSActivityOptions, NSProcessInfo, NSString};

pub struct Activity {
    _token: Retained<ProtocolObject<dyn NSObjectProtocol>>,
}

impl Activity {
    pub fn begin(reason: &str) -> Self {
        let token = NSProcessInfo::processInfo().beginActivityWithOptions_reason(
            NSActivityOptions::UserInitiatedAllowingIdleSystemSleep,
            &NSString::from_str(reason),
        );
        Self { _token: token }
    }
}
