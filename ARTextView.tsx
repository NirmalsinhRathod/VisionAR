import React, {useEffect, useState, useRef} from 'react';
import {
  View,
  Text,
  StyleSheet,
  ActivityIndicator,
  NativeEventEmitter,
  requireNativeComponent,
  ViewStyle,
  Animated,
  Easing,
  Platform,
  TouchableOpacity,
} from 'react-native';

// Design System Constants
const COLORS = {
  background: '#000000',
  surface: 'rgba(18, 18, 18, 0.95)',
  surfaceLight: 'rgba(28, 28, 30, 0.92)',
  primary: '#0A84FF',
  success: '#30D158',
  warning: '#FF9F0A',
  error: '#FF453A',
  textPrimary: '#FFFFFF',
  textSecondary: 'rgba(255, 255, 255, 0.70)',
  textTertiary: 'rgba(255, 255, 255, 0.45)',
  border: 'rgba(255, 255, 255, 0.08)',
  borderLight: 'rgba(255, 255, 255, 0.12)',
  overlay: 'rgba(0, 0, 0, 0.40)',
};

const SPACING = {
  xs: 8,
  sm: 12,
  md: 16,
  lg: 20,
  xl: 24,
  xxl: 32,
};

const TYPOGRAPHY = {
  title: {fontSize: 20, fontWeight: '700' as const, lineHeight: 28},
  headline: {fontSize: 17, fontWeight: '600' as const, lineHeight: 24},
  body: {fontSize: 15, fontWeight: '500' as const, lineHeight: 22},
  caption: {fontSize: 13, fontWeight: '500' as const, lineHeight: 18},
  small: {fontSize: 11, fontWeight: '600' as const, lineHeight: 16},
};

const RADIUS = {
  sm: 8,
  md: 12,
  lg: 16,
  xl: 20,
  full: 9999,
};

// Native component interface
interface NativeARTextProps {
  style?: ViewStyle;
  text: string;
}

const NativeARText = requireNativeComponent<NativeARTextProps>('NativeARText');

// AR State interface
interface ARTextState {
  type: string;
  message: string;
  arSessionReady: boolean;
  textRendererReady: boolean;
  planeDetected: boolean;
}

interface ARTextViewProps {
  text: string;
  style?: ViewStyle;
  onBack?: () => void;
}

const ARTextView: React.FC<ARTextViewProps> = ({text, style, onBack}) => {
  const [arState, setArState] = useState<ARTextState>({
    type: 'INITIALIZING',
    message: 'Initializing AR...',
    arSessionReady: false,
    textRendererReady: false,
    planeDetected: false,
  });

  const [showOverlay, setShowOverlay] = useState(true);

  // Animation values
  const fadeAnim = useRef(new Animated.Value(0)).current;
  const slideDownAnim = useRef(new Animated.Value(-50)).current;
  const slideUpAnim = useRef(new Animated.Value(50)).current;
  const pulseAnim = useRef(new Animated.Value(1)).current;

  useEffect(() => {
    // Refined entrance animation
    Animated.parallel([
      Animated.timing(fadeAnim, {
        toValue: 1,
        duration: 500,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.spring(slideDownAnim, {
        toValue: 0,
        tension: 40,
        friction: 9,
        useNativeDriver: true,
      }),
      Animated.spring(slideUpAnim, {
        toValue: 0,
        tension: 40,
        friction: 9,
        useNativeDriver: true,
      }),
    ]).start();

    // Subtle pulsing animation
    const pulseAnimation = Animated.loop(
      Animated.sequence([
        Animated.timing(pulseAnim, {
          toValue: 1.05,
          duration: 1200,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
        Animated.timing(pulseAnim, {
          toValue: 1,
          duration: 1200,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
      ]),
    );
    pulseAnimation.start();

    return () => {
      pulseAnimation.stop();
    };
  }, []);

  useEffect(() => {
    const eventEmitter = new NativeEventEmitter();

    const subscription = eventEmitter.addListener(
      'onARTextStateChange',
      (event: ARTextState) => {
        console.log('AR Text State Update:', event);
        setArState(event);

        if (event.type === 'TEXT_PLACED') {
          setTimeout(() => {
            Animated.timing(fadeAnim, {
              toValue: 0,
              duration: 400,
              easing: Easing.out(Easing.cubic),
              useNativeDriver: true,
            }).start(() => {
              setShowOverlay(false);
              fadeAnim.setValue(1);
            });
          }, 2000);
        } else if (event.type === 'TEXT_SELECTED') {
          // Show rotation hint when text is selected
          console.log('Text selected for rotation');
        } else if (event.type === 'TEXT_ROTATING') {
          // Text is being rotated
          console.log('Rotating text');
        } else if (
          event.type === 'PLACEMENT_BLOCKED' ||
          event.type === 'RENDERER_INITIALIZING' ||
          event.type === 'AR_INITIALIZING'
        ) {
          setShowOverlay(true);
        }
      },
    );

    return () => {
      subscription.remove();
    };
  }, []);

  const getStatusMessage = () => {
    switch (arState.type) {
      case 'AR_INITIALIZING':
        return 'Initializing AR Session';
      case 'RENDERER_INITIALIZING':
        return 'Preparing Renderer';
      case 'AR_SESSION_READY':
        return 'Scan Environment';
      case 'TEXT_RENDERER_READY':
        return 'Renderer Ready';
      case 'PLANE_DETECTED':
        return 'Surface Detected';
      case 'TEXT_PLACED':
        return 'Text Placed Successfully';
      case 'TEXT_UPDATING':
        return 'Updating Content';
      case 'TEXT_UPDATED':
        return 'Content Updated';
      case 'PLACEMENT_BLOCKED':
        return arState.message;
      case 'AR_ERROR':
        return arState.message;
      default:
        return arState.message;
    }
  };

  const getStatusColor = () => {
    if (arState.type.includes('ERROR')) return COLORS.error;
    if (arState.type === 'TEXT_PLACED') return COLORS.success;
    if (arState.type === 'PLANE_DETECTED') return COLORS.primary;
    return COLORS.warning;
  };

  const canPlaceText =
    arState.arSessionReady &&
    arState.textRendererReady &&
    arState.planeDetected;

  const showLoadingSpinner =
    !canPlaceText &&
    arState.type !== 'TEXT_PLACED' &&
    !arState.type.includes('ERROR');

  const getProgressPercentage = () => {
    let count = 0;
    if (arState.arSessionReady) count++;
    if (arState.textRendererReady) count++;
    if (arState.planeDetected) count++;
    return (count / 3) * 100;
  };

  const StatusIndicator = ({active}: {active: boolean}) => (
    <View
      style={[styles.statusIndicator, active && styles.statusIndicatorActive]}>
      {active && <View style={styles.statusIndicatorDot} />}
    </View>
  );

  return (
    <View style={[styles.container, style]}>
      {/* Native AR View */}
      <NativeARText style={styles.arView} text={text} />

      {/* Always Visible Back Button */}
      {onBack && (
        <View style={styles.persistentNavbar} pointerEvents="box-none">
          <TouchableOpacity
            style={styles.backButton}
            onPress={onBack}
            activeOpacity={0.7}>
            <Text style={styles.backButtonText}>←</Text>
          </TouchableOpacity>
        </View>
      )}

      {/* Status Overlay */}
      {showOverlay && (
        <Animated.View
          style={[
            styles.statusOverlay,
            {
              opacity: fadeAnim,
              transform: [{translateY: slideDownAnim}],
            },
          ]}
          pointerEvents="box-none">
          {/* Status Card */}
          <View style={styles.statusCard}>
            <View style={styles.statusHeader}>
              <View style={styles.statusBadge}>
                {showLoadingSpinner ? (
                  <ActivityIndicator size="small" color={getStatusColor()} />
                ) : (
                  <View
                    style={[
                      styles.statusDot,
                      {backgroundColor: getStatusColor()},
                    ]}
                  />
                )}
                <Text style={styles.statusText}>{getStatusMessage()}</Text>
              </View>
            </View>

            {/* Progress Indicators */}
            <View style={styles.progressRow}>
              <View style={styles.progressItem}>
                <StatusIndicator active={arState.arSessionReady} />
                <Text style={styles.progressLabel}>AR Session</Text>
              </View>
              <View style={styles.progressDivider} />
              <View style={styles.progressItem}>
                <StatusIndicator active={arState.textRendererReady} />
                <Text style={styles.progressLabel}>Renderer</Text>
              </View>
              <View style={styles.progressDivider} />
              <View style={styles.progressItem}>
                <StatusIndicator active={arState.planeDetected} />
                <Text style={styles.progressLabel}>Surface</Text>
              </View>
            </View>

            {/* Progress Bar */}
            <View style={styles.progressBarTrack}>
              <Animated.View
                style={[
                  styles.progressBarFill,
                  {
                    width: `${getProgressPercentage()}%`,
                    backgroundColor: getStatusColor(),
                  },
                ]}
              />
            </View>
          </View>
        </Animated.View>
      )}

      {/* Bottom Instructions */}
      {showOverlay && (
        <Animated.View
          style={[
            styles.footer,
            {
              opacity: fadeAnim,
              transform: [{translateY: slideUpAnim}],
            },
          ]}
          pointerEvents="box-none">
          {/* Instruction Card */}
          {!canPlaceText && !arState.type.includes('ERROR') && (
            <View style={styles.instructionCard}>
              <Text style={styles.instructionTitle}>Getting Ready</Text>
              <Text style={styles.instructionText}>
                {!arState.arSessionReady &&
                  'Point your camera at a well-lit flat surface'}
                {arState.arSessionReady &&
                  !arState.textRendererReady &&
                  'Initializing text renderer...'}
                {arState.textRendererReady &&
                  !arState.planeDetected &&
                  'Move your device slowly to detect surfaces'}
              </Text>
            </View>
          )}

          {/* Ready State */}
          {canPlaceText && (
            <Animated.View
              style={[styles.readyCard, {transform: [{scale: pulseAnim}]}]}>
              <View style={styles.readyIndicator} />
              <View style={styles.readyContent}>
                <Text style={styles.readyTitle}>Ready to Place</Text>
                <Text style={styles.readySubtitle}>
                  Tap to place • Drag to rotate
                </Text>
              </View>
            </Animated.View>
          )}
        </Animated.View>
      )}

      {/* Post-Placement UI */}
      {!showOverlay && arState.type === 'TEXT_PLACED' && (
        <View style={styles.placedOverlay}>
          <View style={styles.placedCard}>
            <View style={styles.instructionRow}>
              <Text style={styles.instructionIcon}>🔄</Text>
              <Text style={styles.placedText}>
                Drag on text to rotate horizontally
              </Text>
            </View>
            <View style={styles.instructionDivider} />
            <View style={styles.instructionRow}>
              <Text style={styles.instructionIcon}>👆</Text>
              <Text style={styles.placedText}>
                Tap anywhere to place more text
              </Text>
            </View>
          </View>
        </View>
      )}

      {/* Error Display */}
      {arState.type.includes('ERROR') && (
        <View style={styles.errorContainer}>
          <View style={styles.errorCard}>
            <View style={styles.errorIconContainer}>
              <Text style={styles.errorIcon}>!</Text>
            </View>
            <Text style={styles.errorTitle}>Unable to Continue</Text>
            <Text style={styles.errorMessage}>{getStatusMessage()}</Text>
          </View>
        </View>
      )}

      {/* Center Reticle */}
      {showOverlay && canPlaceText && (
        <View style={styles.reticle} pointerEvents="none">
          <View style={styles.reticleRing} />
          <View style={styles.reticleDot} />
        </View>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: COLORS.background,
  },
  arView: {
    flex: 1,
  },

  // Persistent Navigation - Always Visible
  persistentNavbar: {
    position: 'absolute',
    top: Platform.OS === 'ios' ? 50 : SPACING.lg,
    left: SPACING.md,
    right: SPACING.md,
    zIndex: 1000,
  },
  backButton: {
    width: 40,
    height: 40,
    borderRadius: RADIUS.md,
    backgroundColor: COLORS.surface,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: COLORS.border,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.25,
    shadowRadius: 8,
    elevation: 4,
  },
  backButtonText: {
    fontSize: 22,
    color: COLORS.textPrimary,
    lineHeight: 24,
  },

  // Status Overlay
  statusOverlay: {
    position: 'absolute',
    top: Platform.OS === 'ios' ? 100 : 70,
    left: SPACING.md,
    right: SPACING.md,
  },

  // Status Card Styles
  statusCard: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.lg,
    padding: SPACING.md,
    borderWidth: 1,
    borderColor: COLORS.borderLight,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 4},
    shadowOpacity: 0.3,
    shadowRadius: 12,
    elevation: 6,
  },
  statusHeader: {
    marginBottom: SPACING.md,
  },
  statusBadge: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: SPACING.sm,
  },
  statusText: {
    ...TYPOGRAPHY.body,
    color: COLORS.textPrimary,
    flex: 1,
  },
  progressRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: SPACING.sm,
  },
  progressItem: {
    flex: 1,
    alignItems: 'center',
  },
  progressLabel: {
    ...TYPOGRAPHY.small,
    color: COLORS.textSecondary,
    marginTop: 6,
    textAlign: 'center',
  },
  progressDivider: {
    width: 1,
    height: 24,
    backgroundColor: COLORS.border,
    marginHorizontal: SPACING.xs,
  },
  statusIndicator: {
    width: 20,
    height: 20,
    borderRadius: 10,
    borderWidth: 2,
    borderColor: COLORS.textTertiary,
    backgroundColor: COLORS.overlay,
    alignItems: 'center',
    justifyContent: 'center',
  },
  statusIndicatorActive: {
    borderColor: COLORS.primary,
  },
  statusIndicatorDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: COLORS.primary,
  },
  progressBarTrack: {
    height: 4,
    backgroundColor: COLORS.overlay,
    borderRadius: 2,
    overflow: 'hidden',
    marginTop: SPACING.xs,
  },
  progressBarFill: {
    height: '100%',
    borderRadius: 2,
  },

  // Footer Styles
  footer: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    paddingHorizontal: SPACING.md,
    paddingBottom: Platform.OS === 'ios' ? 40 : SPACING.lg,
  },
  instructionCard: {
    backgroundColor: COLORS.surfaceLight,
    borderRadius: RADIUS.lg,
    padding: SPACING.lg,
    borderWidth: 1,
    borderColor: COLORS.borderLight,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: -4},
    shadowOpacity: 0.2,
    shadowRadius: 12,
    elevation: 6,
  },
  instructionTitle: {
    ...TYPOGRAPHY.headline,
    color: COLORS.textPrimary,
    marginBottom: SPACING.xs,
  },
  instructionText: {
    ...TYPOGRAPHY.caption,
    color: COLORS.textSecondary,
    lineHeight: 20,
  },
  readyCard: {
    backgroundColor: 'rgba(48, 209, 88, 0.15)',
    borderRadius: RADIUS.lg,
    padding: SPACING.lg,
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1.5,
    borderColor: COLORS.success,
    shadowColor: COLORS.success,
    shadowOffset: {width: 0, height: 4},
    shadowOpacity: 0.3,
    shadowRadius: 12,
    elevation: 6,
  },
  readyIndicator: {
    width: 12,
    height: 12,
    borderRadius: 6,
    backgroundColor: COLORS.success,
    marginRight: SPACING.md,
  },
  readyContent: {
    flex: 1,
  },
  readyTitle: {
    ...TYPOGRAPHY.headline,
    color: COLORS.success,
    marginBottom: 2,
  },
  readySubtitle: {
    ...TYPOGRAPHY.caption,
    color: COLORS.textSecondary,
  },

  // Placed Overlay
  placedOverlay: {
    position: 'absolute',
    bottom: Platform.OS === 'ios' ? 40 : SPACING.lg,
    left: SPACING.md,
    right: SPACING.md,
  },
  placedCard: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.lg,
    padding: SPACING.lg,
    borderWidth: 1,
    borderColor: COLORS.borderLight,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 4},
    shadowOpacity: 0.25,
    shadowRadius: 12,
    elevation: 6,
  },
  instructionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: SPACING.xs,
  },
  instructionIcon: {
    fontSize: 20,
    marginRight: SPACING.sm,
  },
  instructionDivider: {
    height: 1,
    backgroundColor: COLORS.border,
    marginVertical: SPACING.sm,
  },
  placedText: {
    ...TYPOGRAPHY.caption,
    color: COLORS.textSecondary,
    flex: 1,
  },

  // Error Styles
  errorContainer: {
    position: 'absolute',
    top: Platform.OS === 'ios' ? 120 : 100,
    left: SPACING.md,
    right: SPACING.md,
  },
  errorCard: {
    backgroundColor: COLORS.surface,
    borderRadius: RADIUS.lg,
    padding: SPACING.xl,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: COLORS.error,
    shadowColor: COLORS.error,
    shadowOffset: {width: 0, height: 4},
    shadowOpacity: 0.3,
    shadowRadius: 16,
    elevation: 8,
  },
  errorIconContainer: {
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: COLORS.error,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: SPACING.md,
  },
  errorIcon: {
    fontSize: 32,
    fontWeight: '700',
    color: COLORS.textPrimary,
  },
  errorTitle: {
    ...TYPOGRAPHY.title,
    color: COLORS.textPrimary,
    marginBottom: SPACING.xs,
  },
  errorMessage: {
    ...TYPOGRAPHY.caption,
    color: COLORS.textSecondary,
    textAlign: 'center',
    lineHeight: 20,
  },

  // Reticle Styles
  reticle: {
    position: 'absolute',
    top: '50%',
    left: '50%',
    width: 48,
    height: 48,
    marginTop: -24,
    marginLeft: -24,
    alignItems: 'center',
    justifyContent: 'center',
  },
  reticleRing: {
    position: 'absolute',
    width: 48,
    height: 48,
    borderRadius: 24,
    borderWidth: 2,
    borderColor: COLORS.primary,
    opacity: 0.6,
  },
  reticleDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: COLORS.primary,
  },
});

export default ARTextView;
