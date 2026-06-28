import { useEffect, useRef } from 'react';
import { Animated, Easing, StyleSheet, View } from 'react-native';

import { SearchSignalsPanel } from './SearchSignalsPanel';
import { SosSignalButton } from './SosSignalButton';

type ActionMode = 'sos' | 'search';

type ActionModeContentProps = {
  activeMode: ActionMode;
  onSosSendingChange?: (isSending: boolean) => void;
};

export function ActionModeContent({ activeMode, onSosSendingChange }: ActionModeContentProps) {
  const slideProgress = useRef(new Animated.Value(activeMode === 'search' ? 1 : 0)).current;
  const isSearching = activeMode === 'search';

  useEffect(() => {
    Animated.timing(slideProgress, {
      duration: 260,
      easing: Easing.out(Easing.cubic),
      toValue: isSearching ? 1 : 0,
      useNativeDriver: true,
    }).start();
  }, [isSearching, slideProgress]);

  const sosStyle = {
    opacity: slideProgress.interpolate({
      inputRange: [0, 0.45, 1],
      outputRange: [1, 0, 0],
    }),
    transform: [
      {
        translateX: slideProgress.interpolate({
          inputRange: [0, 1],
          outputRange: [0, -42],
        }),
      },
    ],
  };

  const searchStyle = {
    opacity: slideProgress.interpolate({
      inputRange: [0, 0.55, 1],
      outputRange: [0, 0, 1],
    }),
    transform: [
      {
        translateX: slideProgress.interpolate({
          inputRange: [0, 1],
          outputRange: [42, 0],
        }),
      },
    ],
  };

  return (
    <View style={styles.container}>
      <Animated.View
        pointerEvents={isSearching ? 'none' : 'auto'}
        style={[styles.panel, styles.sosPanel, sosStyle]}
      >
        <SosSignalButton onSendingChange={onSosSendingChange} />
      </Animated.View>
      <Animated.View
        pointerEvents={isSearching ? 'auto' : 'none'}
        style={[styles.panel, styles.searchPanel, searchStyle]}
      >
        <SearchSignalsPanel />
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    overflow: 'hidden',
    position: 'relative',
  },
  panel: {
    alignItems: 'center',
    bottom: 0,
    left: 0,
    position: 'absolute',
    right: 0,
    top: 0,
  },
  sosPanel: {
    justifyContent: 'flex-start',
  },
  searchPanel: {
    justifyContent: 'flex-start',
  },
});
