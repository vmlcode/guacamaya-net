import { MaterialIcons } from '@expo/vector-icons';
import { useEffect, useRef, useState } from 'react';
import { Animated, Pressable, StyleSheet, Text, View } from 'react-native';

import { colors, type } from '../theme';

type ActionMode = 'sos' | 'search';

type EmergencyActionsProps = {
  activeMode?: ActionMode;
  onModeChange?: (mode: ActionMode) => void;
};

export function EmergencyActions({ activeMode: controlledMode, onModeChange }: EmergencyActionsProps) {
  const [localMode, setLocalMode] = useState<ActionMode>('sos');
  const [trackWidth, setTrackWidth] = useState(0);
  const slideProgress = useRef(new Animated.Value(0)).current;
  const activeMode = controlledMode ?? localMode;
  const isSearching = activeMode === 'search';
  const segmentWidth = Math.max((trackWidth - 8) / 2, 0);

  useEffect(() => {
    Animated.timing(slideProgress, {
      duration: 180,
      toValue: isSearching ? 1 : 0,
      useNativeDriver: true,
    }).start();
  }, [isSearching, slideProgress]);

  const thumbTranslateX = slideProgress.interpolate({
    inputRange: [0, 1],
    outputRange: [0, segmentWidth],
  });

  const setActiveMode = (mode: ActionMode) => {
    setLocalMode(mode);
    onModeChange?.(mode);
  };

  return (
    <View style={styles.container} onLayout={(event) => setTrackWidth(event.nativeEvent.layout.width)}>
      <Animated.View
        pointerEvents="none"
        style={[
          styles.activeThumb,
          isSearching ? styles.searchThumb : styles.sosThumb,
          {
            width: segmentWidth,
            transform: [{ translateX: thumbTranslateX }],
          },
        ]}
      />

      <Pressable
        accessibilityRole="button"
        accessibilityState={{ selected: activeMode === 'sos' }}
        onPress={() => setActiveMode('sos')}
        style={styles.segment}
      >
        <MaterialIcons
          name="emergency"
          size={18}
          color={activeMode === 'sos' ? colors.ink : colors.mutedLight}
        />
        <Text style={[styles.segmentText, activeMode === 'sos' && styles.activeSegmentText]}>Enviar SOS</Text>
      </Pressable>

      <Pressable
        accessibilityRole="switch"
        accessibilityState={{ checked: isSearching }}
        onPress={() => setActiveMode('search')}
        style={styles.segment}
      >
        <MaterialIcons
          name="settings-input-antenna"
          size={16}
          color={isSearching ? colors.onPrimary : colors.mutedLight}
        />
        <Text style={[styles.segmentText, isSearching && styles.searchSegmentText]}>Buscar</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    backgroundColor: colors.surfaceSoft,
    borderColor: colors.hairline,
    borderRadius: 8,
    borderWidth: 1,
    flexDirection: 'row',
    minHeight: 48,
    padding: 4,
  },
  segment: {
    alignItems: 'center',
    borderRadius: 8,
    flex: 1,
    flexDirection: 'row',
    gap: 8,
    height: 40,
    justifyContent: 'center',
  },
  activeThumb: {
    borderRadius: 8,
    height: 40,
    left: 4,
    position: 'absolute',
    top: 4,
  },
  sosThumb: {
    backgroundColor: colors.danger,
  },
  searchThumb: {
    backgroundColor: colors.primary,
  },
  segmentText: {
    ...type.label,
    color: colors.mutedLight,
  },
  activeSegmentText: {
    color: colors.ink,
  },
  searchSegmentText: {
    color: colors.onPrimary,
  },
});
