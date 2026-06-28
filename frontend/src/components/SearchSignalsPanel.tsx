import { MaterialIcons } from '@expo/vector-icons';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Animated,
  LayoutAnimation,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  UIManager,
  View,
} from 'react-native';

import { colors, type } from '../theme';

const signals = [
  {
    age: '2 h',
    ageMinutes: 120,
    badgeColor: '#9B5CFF',
    distance: '~430 m',
    icon: 'home' as const,
    id: '1d90',
    label: 'Refugio',
    location: '10.4951, -66.8688',
    severity: 'normal',
  },
  {
    age: '47 m',
    ageMinutes: 47,
    badgeColor: '#2F9BFF',
    distance: '~280 m',
    icon: 'water-drop' as const,
    id: 'b2c7',
    label: 'Agua',
    location: '10.4928, -66.8705',
    severity: 'normal',
  },
  {
    age: '6 m',
    ageMinutes: 6,
    badgeColor: colors.danger,
    distance: '~120 m',
    icon: 'emergency' as const,
    id: '7b3e',
    label: 'Auxilio',
    location: '10.4934, -66.8712',
    severity: 'critical',
  },
  {
    age: '40 s',
    ageMinutes: 0.67,
    badgeColor: '#2FD6C4',
    distance: '~80 m',
    icon: 'medical-services' as const,
    id: 'e0a1',
    label: 'Medica',
    location: '10.4919, -66.8699',
    severity: 'normal',
  },
];

const sortOptions = [
  { id: 'oldest', label: 'Mas antiguas primero' },
  { id: 'newest', label: 'Mas nuevas primero' },
  { id: 'sos', label: 'Senales SOS primero' },
] as const;

type SortMode = (typeof sortOptions)[number]['id'];
type SearchViewMode = 'list' | 'radar';

if (Platform.OS === 'android') {
  UIManager.setLayoutAnimationEnabledExperimental?.(true);
}

export function SearchSignalsPanel() {
  const [sortMode, setSortMode] = useState<SortMode>('oldest');
  const [viewMode, setViewMode] = useState<SearchViewMode>('list');
  const [trackWidth, setTrackWidth] = useState(0);
  const viewSlideProgress = useRef(new Animated.Value(0)).current;
  const activeSortLabel = sortOptions.find((option) => option.id === sortMode)?.label;
  const isRadar = viewMode === 'radar';
  const segmentWidth = Math.max((trackWidth - 8) / 2, 0);

  const sortedSignals = useMemo(() => {
    const orderedSignals = [...signals];

    if (sortMode === 'newest') {
      return orderedSignals.sort((a, b) => a.ageMinutes - b.ageMinutes);
    }

    if (sortMode === 'sos') {
      return orderedSignals.sort((a, b) => {
        if (a.severity === b.severity) return a.ageMinutes - b.ageMinutes;
        return a.severity === 'critical' ? -1 : 1;
      });
    }

    return orderedSignals.sort((a, b) => b.ageMinutes - a.ageMinutes);
  }, [sortMode]);

  useEffect(() => {
    Animated.timing(viewSlideProgress, {
      duration: 180,
      toValue: isRadar ? 1 : 0,
      useNativeDriver: true,
    }).start();
  }, [isRadar, viewSlideProgress]);

  const handleSortPress = () => {
    LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
    setSortMode((current) => {
      const currentIndex = sortOptions.findIndex((option) => option.id === current);
      return sortOptions[(currentIndex + 1) % sortOptions.length].id;
    });
  };

  const thumbTranslateX = viewSlideProgress.interpolate({
    inputRange: [0, 1],
    outputRange: [0, segmentWidth],
  });

  return (
    <View style={styles.container}>
      <View
        style={styles.viewSwitch}
        onLayout={(event) => setTrackWidth(event.nativeEvent.layout.width)}
      >
        <Animated.View
          pointerEvents="none"
          style={[
            styles.viewThumb,
            {
              width: segmentWidth,
              transform: [{ translateX: thumbTranslateX }],
            },
          ]}
        />

        <Pressable
          accessibilityRole="button"
          accessibilityState={{ selected: viewMode === 'list' }}
          onPress={() => setViewMode('list')}
          style={styles.viewSegment}
        >
          <MaterialIcons
            name="format-list-bulleted"
            size={15}
            color={viewMode === 'list' ? colors.onPrimary : colors.mutedLight}
          />
          <Text style={[styles.viewText, viewMode === 'list' && styles.activeViewText]}>Lista</Text>
        </Pressable>

        <Pressable
          accessibilityRole="button"
          accessibilityState={{ selected: isRadar }}
          onPress={() => setViewMode('radar')}
          style={styles.viewSegment}
        >
          <MaterialIcons
            name="gps-fixed"
            size={15}
            color={isRadar ? colors.onPrimary : colors.mutedLight}
          />
          <Text style={[styles.viewText, isRadar && styles.activeViewText]}>Radar</Text>
        </Pressable>
      </View>

      {viewMode === 'list' ? (
        <>
          <View style={styles.headerRow}>
            <Text style={styles.summary}>
              7 señales - <Text style={styles.criticalText}>2 criticas</Text>
            </Text>
            <Pressable
              accessibilityRole="button"
              accessibilityState={{ selected: true }}
              onPress={handleSortPress}
              style={styles.sortChip}
            >
              <MaterialIcons name="swap-vert" size={15} color={colors.ink} />
              <Text style={styles.sortText}>{activeSortLabel}</Text>
            </Pressable>
          </View>

          <View style={styles.signalList}>
            {sortedSignals.map((signal) => (
              <View
                key={signal.id}
                style={[
                  styles.signalCard,
                  signal.severity === 'critical' && styles.criticalCard,
                ]}
              >
                <View style={styles.ageColumn}>
                  <View
                    style={[
                      styles.ageDot,
                      signal.severity === 'critical' && styles.criticalAgeDot,
                    ]}
                  />
                  <Text style={styles.ageText}>hace</Text>
                  <Text style={styles.ageValue}>{signal.age}</Text>
                </View>

                <View style={styles.signalBody}>
                  <View style={styles.badgeRow}>
                    <View
                      style={[
                        styles.signalBadge,
                        { backgroundColor: signal.badgeColor },
                      ]}
                    >
                      <MaterialIcons name={signal.icon} size={12} color={colors.ink} />
                      <Text style={styles.signalBadgeText}>{signal.label}</Text>
                    </View>
                    {signal.severity === 'critical' && (
                      <View style={styles.criticalBadge}>
                        <MaterialIcons name="warning" size={11} color={colors.ink} />
                        <Text style={styles.criticalBadgeText}>CRITICO</Text>
                      </View>
                    )}
                  </View>

                  <Text style={styles.nodeLine}>
                    node {signal.id} - {signal.location}
                  </Text>

                  <View style={styles.metaRow}>
                    <View style={styles.unconfirmedBadge}>
                      <MaterialIcons name="warning" size={10} color={colors.onPrimary} />
                      <Text style={styles.unconfirmedText}>NO CONFIRMADO</Text>
                    </View>
                    <Text style={styles.distanceText}>{signal.distance}</Text>
                  </View>
                </View>

                <MaterialIcons
                  name="chevron-right"
                  size={22}
                  color={signal.severity === 'critical' ? colors.danger : colors.muted}
                />
              </View>
            ))}
          </View>
        </>
      ) : (
        <View style={styles.radarPanel}>
          <MaterialIcons name="gps-fixed" size={34} color={colors.primary} />
          <Text style={styles.radarTitle}>Radar</Text>
          <Text style={styles.radarCopy}>Escaneando senales cercanas</Text>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    paddingHorizontal: 4,
    paddingTop: 0,
    width: '100%',
  },
  viewSwitch: {
    alignItems: 'center',
    backgroundColor: colors.surfaceSoft,
    borderColor: colors.hairline,
    borderRadius: 8,
    borderWidth: 1,
    flexDirection: 'row',
    minHeight: 48,
    padding: 4,
  },
  viewThumb: {
    backgroundColor: colors.primary,
    borderRadius: 8,
    height: 40,
    left: 4,
    position: 'absolute',
    top: 4,
  },
  viewSegment: {
    alignItems: 'center',
    borderRadius: 8,
    flex: 1,
    flexDirection: 'row',
    gap: 8,
    height: 40,
    justifyContent: 'center',
  },
  viewText: {
    ...type.label,
    color: colors.mutedLight,
  },
  activeViewText: {
    color: colors.onPrimary,
  },
  headerRow: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 14,
  },
  summary: {
    ...type.monoCaption,
    color: colors.mutedLight,
    fontSize: 12,
    lineHeight: 16,
  },
  criticalText: {
    color: colors.danger,
    fontFamily: 'JetBrainsMono_700Bold',
  },
  sortChip: {
    alignItems: 'center',
    backgroundColor: colors.surfaceSoft,
    borderColor: colors.hairline,
    borderRadius: 999,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 5,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  sortText: {
    ...type.label,
    color: colors.ink,
    fontSize: 10,
    lineHeight: 13,
  },
  signalList: {
    gap: 10,
    marginTop: 10,
  },
  signalCard: {
    alignItems: 'center',
    backgroundColor: '#1A1A1A',
    borderColor: colors.hairline,
    borderRadius: 8,
    borderWidth: 1,
    flexDirection: 'row',
    minHeight: 86,
    paddingHorizontal: 12,
    paddingVertical: 11,
  },
  criticalCard: {
    backgroundColor: 'rgba(255, 69, 58, 0.18)',
    borderColor: colors.danger,
  },
  ageColumn: {
    alignItems: 'center',
    marginRight: 12,
    width: 30,
  },
  ageDot: {
    backgroundColor: colors.muted,
    borderRadius: 6,
    height: 12,
    marginBottom: 5,
    width: 12,
  },
  criticalAgeDot: {
    backgroundColor: colors.ink,
  },
  ageText: {
    ...type.monoCaption,
    color: colors.mutedLight,
    fontSize: 8,
    lineHeight: 10,
  },
  ageValue: {
    ...type.label,
    color: colors.ink,
    fontSize: 9,
    lineHeight: 11,
  },
  signalBody: {
    flex: 1,
  },
  badgeRow: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 7,
  },
  signalBadge: {
    alignItems: 'center',
    borderRadius: 999,
    flexDirection: 'row',
    gap: 4,
    paddingHorizontal: 8,
    paddingVertical: 5,
  },
  signalBadgeText: {
    ...type.label,
    color: colors.ink,
    fontSize: 11,
    lineHeight: 13,
  },
  criticalBadge: {
    alignItems: 'center',
    backgroundColor: colors.danger,
    borderRadius: 999,
    flexDirection: 'row',
    gap: 4,
    paddingHorizontal: 8,
    paddingVertical: 5,
  },
  criticalBadgeText: {
    ...type.label,
    color: colors.ink,
    fontSize: 10,
    lineHeight: 12,
  },
  nodeLine: {
    ...type.label,
    color: colors.ink,
    fontSize: 10,
    lineHeight: 14,
    marginTop: 8,
  },
  metaRow: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 8,
    marginTop: 7,
  },
  unconfirmedBadge: {
    alignItems: 'center',
    backgroundColor: '#A56800',
    borderRadius: 999,
    flexDirection: 'row',
    gap: 4,
    paddingHorizontal: 8,
    paddingVertical: 4,
  },
  unconfirmedText: {
    ...type.label,
    color: colors.onPrimary,
    fontSize: 8,
    lineHeight: 10,
  },
  distanceText: {
    ...type.monoCaption,
    color: colors.mutedLight,
    fontSize: 10,
    lineHeight: 12,
  },
  radarPanel: {
    alignItems: 'center',
    flex: 1,
    justifyContent: 'center',
    paddingBottom: 80,
  },
  radarTitle: {
    ...type.label,
    color: colors.ink,
    fontSize: 18,
    lineHeight: 24,
    marginTop: 12,
  },
  radarCopy: {
    ...type.monoCaption,
    color: colors.mutedLight,
    fontSize: 11,
    lineHeight: 15,
    marginTop: 4,
  },
});
