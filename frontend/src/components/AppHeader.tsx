import { useEffect, useRef, useState } from 'react';
import { Animated, StyleSheet, Text, View } from 'react-native';

import { colors, type } from '../theme';
import { IconButton } from './IconButton';

type AppHeaderProps = {
  isListening?: boolean;
};

export function AppHeader({ isListening = false }: AppHeaderProps) {
  const [showListeningChip, setShowListeningChip] = useState(isListening);
  const blink = useRef(new Animated.Value(1)).current;
  const chipProgress = useRef(new Animated.Value(isListening ? 1 : 0)).current;

  useEffect(() => {
    if (isListening) {
      setShowListeningChip(true);
      Animated.timing(chipProgress, {
        duration: 180,
        toValue: 1,
        useNativeDriver: true,
      }).start();
      return undefined;
    }

    const hideTimer = setTimeout(() => {
      Animated.timing(chipProgress, {
        duration: 220,
        toValue: 0,
        useNativeDriver: true,
      }).start(({ finished }) => {
        if (finished) setShowListeningChip(false);
      });
    }, 5000);

    return () => clearTimeout(hideTimer);
  }, [chipProgress, isListening]);

  useEffect(() => {
    if (!isListening) {
      blink.stopAnimation();
      blink.setValue(1);
      return undefined;
    }

    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(blink, {
          duration: 520,
          toValue: 0.25,
          useNativeDriver: true,
        }),
        Animated.timing(blink, {
          duration: 520,
          toValue: 1,
          useNativeDriver: true,
        }),
      ])
    );

    loop.start();

    return () => loop.stop();
  }, [blink, isListening]);

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <View style={styles.brandName}>
          <Text style={styles.brandText}>Guacamaya</Text>
          <Text style={styles.brandSuffix}>.app</Text>
        </View>
        <View style={styles.actions}>
          {showListeningChip && (
            <Animated.View
              style={[
                styles.listeningChip,
                {
                  opacity: chipProgress,
                  transform: [
                    {
                      translateY: chipProgress.interpolate({
                        inputRange: [0, 1],
                        outputRange: [-6, 0],
                      }),
                    },
                  ],
                },
              ]}
            >
              <Animated.View style={[styles.listeningDot, { opacity: blink }]} />
              <Text style={styles.listeningText}>ESCUCHANDO</Text>
            </Animated.View>
          )}
          <IconButton name="menu" />
        </View>
      </View>
      <View style={styles.separator}>
        <View style={[styles.separatorSegment, styles.separatorYellow]} />
        <View style={[styles.separatorSegment, styles.separatorBlue]} />
        <View style={[styles.separatorSegment, styles.separatorRed]} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {},
  header: {
    alignItems: 'center',
    backgroundColor: colors.canvas,
    flexDirection: 'row',
    height: 60,
    justifyContent: 'space-between',
    paddingLeft: 22,
    paddingRight: 10,
  },
  brandName: {
    alignItems: 'baseline',
    flexDirection: 'row',
    flexShrink: 0,
    paddingRight: 6,
  },
  brandText: {
    ...type.brand,
    color: colors.ink,
  },
  brandSuffix: {
    ...type.brand,
    color: colors.mutedLight,
    minWidth: 44,
  },
  actions: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 8,
  },
  listeningChip: {
    alignItems: 'center',
    backgroundColor: colors.surfaceSoft,
    borderColor: colors.hairline,
    borderRadius: 999,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 7,
    paddingHorizontal: 10,
    paddingVertical: 6,
  },
  listeningDot: {
    backgroundColor: colors.muted,
    borderRadius: 4,
    height: 8,
    width: 8,
  },
  listeningText: {
    ...type.label,
    color: colors.ink,
    fontSize: 9,
    lineHeight: 12,
  },
  separator: {
    flexDirection: 'row',
    height: 1,
    opacity: 0.5,
  },
  separatorSegment: {
    flex: 1,
  },
  separatorYellow: {
    backgroundColor: colors.flagYellow,
  },
  separatorBlue: {
    backgroundColor: colors.flagBlue,
  },
  separatorRed: {
    backgroundColor: colors.flagRed,
  },
});
