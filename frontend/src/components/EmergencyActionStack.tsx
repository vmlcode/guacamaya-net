import { MaterialIcons } from '@expo/vector-icons';
import { useEffect, useRef, useState } from 'react';
import { Animated, Easing, StyleSheet, Text, View } from 'react-native';

import { colors, type } from '../theme';
import { EmergencyActions } from './EmergencyActions';

type ActionMode = 'sos' | 'search';

type EmergencyActionStackProps = {
  activeMode?: ActionMode;
  isSosSending?: boolean;
  onModeChange?: (mode: ActionMode) => void;
};

export function EmergencyActionStack({
  activeMode: controlledMode,
  isSosSending = false,
  onModeChange,
}: EmergencyActionStackProps) {
  const [localMode, setLocalMode] = useState<ActionMode>('sos');
  const bannerProgress = useRef(new Animated.Value(0)).current;
  const blink = useRef(new Animated.Value(1)).current;
  const activeMode = controlledMode ?? localMode;
  const shouldShowBanner = isSosSending && activeMode === 'search';

  useEffect(() => {
    Animated.timing(bannerProgress, {
      duration: shouldShowBanner ? 260 : 180,
      easing: shouldShowBanner ? Easing.out(Easing.cubic) : Easing.in(Easing.cubic),
      toValue: shouldShowBanner ? 1 : 0,
      useNativeDriver: false,
    }).start();
  }, [bannerProgress, shouldShowBanner]);

  useEffect(() => {
    if (!shouldShowBanner) {
      blink.stopAnimation();
      blink.setValue(1);
      return undefined;
    }

    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(blink, {
          duration: 420,
          toValue: 0.35,
          useNativeDriver: true,
        }),
        Animated.timing(blink, {
          duration: 420,
          toValue: 1,
          useNativeDriver: true,
        }),
      ])
    );

    loop.start();

    return () => loop.stop();
  }, [blink, shouldShowBanner]);

  const bannerStyle = {
    height: bannerProgress.interpolate({
      inputRange: [0, 1],
      outputRange: [0, 42],
    }),
    marginBottom: bannerProgress.interpolate({
      inputRange: [0, 1],
      outputRange: [0, 10],
    }),
    opacity: bannerProgress,
    transform: [
      {
        translateY: bannerProgress.interpolate({
          inputRange: [0, 1],
          outputRange: [-8, 0],
        }),
      },
    ],
  };

  return (
    <View style={styles.container}>
      <Animated.View pointerEvents="none" style={[styles.bannerShell, bannerStyle]}>
        <View style={styles.banner}>
          <View style={styles.bannerIcon}>
            <MaterialIcons name="emergency" size={15} color={colors.ink} />
          </View>
          <View style={styles.bannerCopy}>
            <Text style={styles.bannerTitle}>ENVIANDO SOS</Text>
            <Text style={styles.bannerDetail}>Señal de auxilio activa</Text>
          </View>
          <Animated.View style={[styles.liveDot, { opacity: blink }]} />
        </View>
      </Animated.View>

      <EmergencyActions
        activeMode={activeMode}
        onModeChange={(mode) => {
          setLocalMode(mode);
          onModeChange?.(mode);
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    overflow: 'visible',
  },
  bannerShell: {
    overflow: 'hidden',
  },
  banner: {
    alignItems: 'center',
    backgroundColor: 'rgba(255, 69, 58, 0.18)',
    borderColor: 'rgba(255, 69, 58, 0.8)',
    borderRadius: 8,
    borderWidth: 1,
    flexDirection: 'row',
    height: 42,
    paddingHorizontal: 11,
  },
  bannerIcon: {
    alignItems: 'center',
    backgroundColor: colors.danger,
    borderRadius: 13,
    height: 26,
    justifyContent: 'center',
    width: 26,
  },
  bannerCopy: {
    flex: 1,
    marginLeft: 9,
  },
  bannerTitle: {
    ...type.label,
    color: colors.ink,
    fontSize: 12,
    lineHeight: 15,
  },
  bannerDetail: {
    ...type.monoCaption,
    color: colors.mutedLight,
    fontSize: 9,
    lineHeight: 12,
  },
  liveDot: {
    backgroundColor: colors.danger,
    borderRadius: 4,
    height: 8,
    width: 8,
  },
});
