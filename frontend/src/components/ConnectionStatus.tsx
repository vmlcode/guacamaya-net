import { MaterialIcons } from '@expo/vector-icons';
import { useEffect, useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { colors, type } from '../theme';

type ConnectionState = 'offline' | 'syncing' | 'synced' | 'online';

export function ConnectionStatus() {
  const [connectionState, setConnectionState] = useState<ConnectionState>('offline');
  const [dotStep, setDotStep] = useState(1);
  const isConnected = connectionState !== 'offline';

  useEffect(() => {
    if (connectionState !== 'syncing') return undefined;

    const dotsTimer = setInterval(() => {
      setDotStep((current) => (current % 3) + 1);
    }, 500);

    const syncedTimer = setTimeout(() => {
      setConnectionState('synced');
    }, 3000);

    return () => {
      clearInterval(dotsTimer);
      clearTimeout(syncedTimer);
    };
  }, [connectionState]);

  useEffect(() => {
    if (connectionState !== 'synced') return undefined;

    const onlineTimer = setTimeout(() => {
      setConnectionState('online');
    }, 1000);

    return () => clearTimeout(onlineTimer);
  }, [connectionState]);

  const status = useMemo(() => {
    switch (connectionState) {
      case 'syncing':
        return {
          icon: 'wifi' as const,
          iconColor: colors.primary,
          text: `Sincronizando${'.'.repeat(dotStep)}`,
          textStyle: styles.statusText,
        };
      case 'synced':
        return {
          icon: 'wifi' as const,
          iconColor: colors.success,
          text: 'Sincronizado',
          textStyle: styles.syncedText,
        };
      case 'online':
        return {
          icon: 'wifi' as const,
          iconColor: colors.success,
          text: 'Con Wi-Fi',
          textStyle: styles.statusText,
        };
      case 'offline':
      default:
        return {
          icon: 'wifi-off' as const,
          iconColor: colors.mutedLight,
          text: 'Sin internet - modo malla',
          textStyle: styles.statusText,
        };
    }
  }, [connectionState, dotStep]);

  const toggleConnection = () => {
    setDotStep(1);
    setConnectionState((current) => (current === 'offline' ? 'syncing' : 'offline'));
  };

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected: isConnected }}
      onPress={toggleConnection}
      style={styles.container}
    >
      <View style={styles.statusGroup}>
        <MaterialIcons
          name={status.icon}
          size={17}
          color={status.iconColor}
        />
        <Text style={status.textStyle}>{status.text}</Text>
      </View>
      <Text style={styles.nodeText}>node a4f2</Text>
    </Pressable>
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
    justifyContent: 'space-between',
    minHeight: 38,
    paddingLeft: 12,
    paddingRight: 12,
  },
  statusGroup: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 8,
  },
  statusText: {
    ...type.label,
    color: colors.ink,
  },
  syncedText: {
    ...type.label,
    color: colors.success,
  },
  nodeText: {
    ...type.monoCaption,
    color: colors.muted,
  },
});
