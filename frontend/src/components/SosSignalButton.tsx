import { MaterialIcons } from '@expo/vector-icons';
import { useEffect, useRef, useState } from 'react';
import { Animated, Easing, Pressable, StyleSheet, Text, View } from 'react-native';

import { colors, type } from '../theme';

type SosState = 'idle' | 'searching' | 'sending';

type SosSignalButtonProps = {
  onSendingChange?: (isSending: boolean) => void;
};

export function SosSignalButton({ onSendingChange }: SosSignalButtonProps) {
  const [sosState, setSosState] = useState<SosState>('idle');
  const [confirmationFound, setConfirmationFound] = useState(false);
  const [listeningDotCount, setListeningDotCount] = useState(3);
  const pulse = useRef(new Animated.Value(0)).current;
  const liveBlink = useRef(new Animated.Value(1)).current;
  const listeningBlink = useRef(new Animated.Value(1)).current;
  const sendTransition = useRef(new Animated.Value(0)).current;
  const isSearching = sosState === 'searching';
  const isSending = sosState === 'sending';

  useEffect(() => {
    onSendingChange?.(isSending);
  }, [isSending, onSendingChange]);

  useEffect(() => {
    if (!isSearching) return undefined;

    const searchTimer = setTimeout(() => {
      setSosState('sending');
    }, 1800);

    return () => clearTimeout(searchTimer);
  }, [isSearching]);

  useEffect(() => {
    Animated.timing(sendTransition, {
      duration: isSending ? 560 : 180,
      easing: isSending ? Easing.out(Easing.cubic) : Easing.in(Easing.cubic),
      toValue: isSending ? 1 : 0,
      useNativeDriver: true,
    }).start();
  }, [isSending, sendTransition]);

  useEffect(() => {
    if (!isSending) {
      pulse.stopAnimation();
      pulse.setValue(0);
      liveBlink.stopAnimation();
      liveBlink.setValue(1);
      listeningBlink.stopAnimation();
      listeningBlink.setValue(1);
      setConfirmationFound(false);
      return undefined;
    }

    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, {
          duration: 1150,
          toValue: 1,
          useNativeDriver: true,
        }),
        Animated.timing(pulse, {
          duration: 0,
          toValue: 0,
          useNativeDriver: true,
        }),
      ])
    );

    loop.start();

    return () => loop.stop();
  }, [isSending, listeningBlink, liveBlink, pulse]);

  useEffect(() => {
    if (!isSending) return undefined;

    const blinkLoop = Animated.loop(
      Animated.sequence([
        Animated.timing(liveBlink, {
          duration: 420,
          toValue: 0.28,
          useNativeDriver: true,
        }),
        Animated.timing(liveBlink, {
          duration: 420,
          toValue: 1,
          useNativeDriver: true,
        }),
      ])
    );

    blinkLoop.start();

    return () => blinkLoop.stop();
  }, [isSending, liveBlink]);

  useEffect(() => {
    if (!isSending || confirmationFound) {
      listeningBlink.stopAnimation();
      listeningBlink.setValue(1);
      return undefined;
    }

    const blinkLoop = Animated.loop(
      Animated.sequence([
        Animated.timing(listeningBlink, {
          duration: 520,
          toValue: 0.24,
          useNativeDriver: true,
        }),
        Animated.timing(listeningBlink, {
          duration: 520,
          toValue: 1,
          useNativeDriver: true,
        }),
      ])
    );

    blinkLoop.start();

    return () => blinkLoop.stop();
  }, [confirmationFound, isSending, listeningBlink]);

  useEffect(() => {
    if (!isSending || confirmationFound) return undefined;

    const dotsTimer = setInterval(() => {
      setListeningDotCount((current) => (current === 3 ? 1 : current + 1));
    }, 420);

    return () => clearInterval(dotsTimer);
  }, [confirmationFound, isSending]);

  useEffect(() => {
    if (!confirmationFound) return undefined;

    const confirmationTimer = setTimeout(() => {
      setConfirmationFound(false);
    }, 3000);

    return () => clearTimeout(confirmationTimer);
  }, [confirmationFound]);

  const handlePress = () => {
    if (sosState === 'searching') return;
    setSosState((current) => (current === 'sending' ? 'idle' : 'searching'));
  };

  const handleConfirmationPress = () => {
    if (!isSending) return;
    setConfirmationFound(true);
  };

  const pulseStyle = {
    opacity: pulse.interpolate({
      inputRange: [0, 0.7, 1],
      outputRange: [1, 0.52, 0],
    }),
    transform: [
      {
        scale: pulse.interpolate({
          inputRange: [0, 1],
          outputRange: [0.18, 2.65],
        }),
      },
    ],
  };

  const containerMotionStyle = {
    transform: [
      {
        translateY: sendTransition.interpolate({
          inputRange: [0, 1],
          outputRange: [152, 0],
        }),
      },
    ],
  };

  const getRevealStyle = (index: number) => {
    const start = 0.36 + index * 0.12;
    const end = Math.min(start + 0.22, 1);

    return {
      opacity: sendTransition.interpolate({
        inputRange: [0, start, end],
        outputRange: [0, 0, 1],
      }),
      transform: [
        {
          translateY: sendTransition.interpolate({
            inputRange: [0, start, end],
            outputRange: [-10, -10, 0],
          }),
        },
      ],
    };
  };

  return (
    <Animated.View style={[styles.container, containerMotionStyle]}>
      <View style={styles.radarArea}>
        {isSending && (
          <Animated.View pointerEvents="none" style={[styles.pulseRing, pulseStyle]} />
        )}
        <Pressable
          accessibilityRole="button"
          accessibilityState={{ busy: isSearching || isSending, disabled: isSearching }}
          disabled={isSearching}
          onPress={handlePress}
          style={[styles.button, isSearching && styles.buttonSearching]}
        >
          <MaterialIcons name="emergency" size={42} color={colors.ink} />
          <Text style={styles.buttonText}>
            {sosState === 'searching' ? 'BUSCANDO' : isSending ? 'ENVIANDO' : 'TOCAR'}
          </Text>
        </Pressable>
      </View>

      <View style={styles.copy}>
        <Text style={styles.title}>
          {sosState === 'searching'
            ? 'Buscando enlace cercano'
            : isSending
              ? 'Enviando señal SOS'
              : 'Listo para enviar SOS'}
        </Text>
        <Text style={styles.subtitle}>
          {sosState === 'searching'
            ? 'Preparando transmision por la malla'
            : isSending
              ? 'Toca otra vez para detener el envio'
              : 'Toca el boton para transmitir tu auxilio'}
        </Text>
        <View style={styles.locationRow}>
          <MaterialIcons name="location-on" size={14} color={colors.muted} />
          <Text style={styles.locationText}>10.4910, -66.8780</Text>
        </View>
      </View>

      {isSending && (
        <View style={styles.transmissionPanel}>
          <Animated.View style={[styles.chipRow, getRevealStyle(0)]}>
            <View style={[styles.chip, styles.airChip]}>
              <Animated.View style={[styles.chipDot, styles.airDot, { opacity: liveBlink }]} />
              <Text style={[styles.chipText, styles.airText]}>EN EL AIRE</Text>
            </View>
            <View style={[styles.chip, styles.pillChip, styles.helpChip]}>
              <MaterialIcons name="emergency" size={13} color={colors.danger} />
              <Text style={[styles.chipText, styles.pillText]}>Auxilio</Text>
            </View>
            <View style={[styles.chip, styles.pillChip, styles.criticalChip]}>
              <MaterialIcons name="warning" size={12} color={colors.ink} />
              <Text style={[styles.chipText, styles.pillText]}>CRITICO</Text>
            </View>
          </Animated.View>

          <Animated.View style={[styles.metricRow, getRevealStyle(1)]}>
            <View style={styles.metricCard}>
              <Text style={styles.metricValue}>3</Text>
              <Text style={styles.metricLabel}>nodos en alcance</Text>
            </View>
            <View style={styles.metricCard}>
              <Text style={styles.metricValue}>2</Text>
              <Text style={styles.metricLabel}>retransmisiones</Text>
            </View>
          </Animated.View>

          <Animated.Text style={[styles.sectionLabel, getRevealStyle(2)]}>
            REGISTRO DE ACTIVIDAD
          </Animated.Text>
          <Animated.View style={[styles.activityList, getRevealStyle(3)]}>
            <ActivityRow
              color={confirmationFound ? colors.success : colors.mutedLight}
              detail={confirmationFound ? 'respondio nodo a4f2' : 'en vivo'}
              dotOpacity={confirmationFound ? undefined : listeningBlink}
              onPress={handleConfirmationPress}
              title={
                confirmationFound
                  ? 'Confirmacion encontrada'
                  : `Escuchando confirmaciones${'.'.repeat(listeningDotCount)}`
              }
            />
            <ActivityRow
              color={colors.success}
              title="Retransmitido por nodo c4f1 (salto 2)"
              detail="hace 6 s"
            />
            <ActivityRow
              color="#38A8FF"
              title="Bateria 78% incluida en payload"
              detail="hace 8 s"
            />
            <ActivityRow
              color={colors.primary}
              title="SOS compartido - 10.4910, -66.8780"
              detail="hace 9 s - origen"
              hideDivider
            />
          </Animated.View>
        </View>
      )}
    </Animated.View>
  );
}

function ActivityRow({
  color,
  detail,
  dotOpacity,
  hideDivider,
  onPress,
  title,
}: {
  color: string;
  detail: string;
  dotOpacity?: Animated.Value;
  hideDivider?: boolean;
  onPress?: () => void;
  title: string;
}) {
  const Row = onPress ? Pressable : View;
  const dotStyle = [styles.activityDot, { backgroundColor: color }];
  const content = (
    <>
      {dotOpacity ? (
        <Animated.View style={[dotStyle, { opacity: dotOpacity }]} />
      ) : (
        <View style={dotStyle} />
      )}
      <View style={styles.activityCopy}>
        <Text style={styles.activityTitle}>{title}</Text>
        <Text style={styles.activityDetail}>{detail}</Text>
      </View>
    </>
  );

  return (
    <Row
      accessibilityRole={onPress ? 'button' : undefined}
      onPress={onPress}
      style={[styles.activityRow, hideDivider && styles.activityRowLast]}
    >
      {content}
    </Row>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    alignSelf: 'center',
    paddingTop: 0,
    width: '100%',
  },
  radarArea: {
    alignItems: 'center',
    alignSelf: 'center',
    height: 156,
    justifyContent: 'center',
    overflow: 'visible',
    width: 240,
  },
  pulseRing: {
    backgroundColor: 'rgba(255, 69, 58, 0.28)',
    borderColor: '#FF6B61',
    borderRadius: 68,
    borderWidth: 5,
    height: 136,
    position: 'absolute',
    width: 136,
    zIndex: 0,
  },
  button: {
    alignItems: 'center',
    backgroundColor: colors.danger,
    borderRadius: 58,
    gap: 6,
    height: 116,
    justifyContent: 'center',
    position: 'relative',
    width: 116,
    zIndex: 2,
  },
  buttonSearching: {
    opacity: 0.78,
  },
  buttonText: {
    ...type.label,
    color: colors.ink,
    fontSize: 11,
    lineHeight: 14,
  },
  copy: {
    alignItems: 'center',
    gap: 5,
    marginTop: 22,
  },
  title: {
    ...type.label,
    color: colors.ink,
    fontSize: 16,
    lineHeight: 20,
  },
  subtitle: {
    ...type.monoCaption,
    color: colors.mutedLight,
    fontSize: 11,
    lineHeight: 15,
    textAlign: 'center',
  },
  locationRow: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 5,
    marginTop: 6,
  },
  locationText: {
    ...type.monoCaption,
    color: colors.muted,
  },
  transmissionPanel: {
    alignSelf: 'stretch',
    marginTop: 18,
    paddingHorizontal: 4,
  },
  chipRow: {
    flexDirection: 'row',
    gap: 9,
    justifyContent: 'center',
  },
  chip: {
    alignItems: 'center',
    borderColor: colors.hairline,
    borderRadius: 6,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 7,
    paddingHorizontal: 13,
    paddingVertical: 8,
  },
  airChip: {
    borderColor: 'rgba(255, 69, 58, 0.7)',
    borderRadius: 999,
  },
  pillChip: {
    borderRadius: 999,
    borderWidth: 0,
    paddingHorizontal: 12,
    paddingVertical: 9,
  },
  helpChip: {
    backgroundColor: '#1A1A1A',
    paddingRight: 16,
  },
  criticalChip: {
    backgroundColor: colors.danger,
  },
  chipDot: {
    borderRadius: 3,
    height: 6,
    width: 6,
  },
  airDot: {
    backgroundColor: colors.danger,
  },
  chipText: {
    ...type.label,
    color: colors.mutedLight,
    fontSize: 11,
    lineHeight: 15,
  },
  airText: {
    color: colors.primary,
  },
  pillText: {
    color: colors.ink,
    fontSize: 11,
    lineHeight: 13,
    textTransform: 'none',
  },
  metricRow: {
    flexDirection: 'row',
    gap: 10,
    marginTop: 16,
  },
  metricCard: {
    backgroundColor: colors.surfaceSoft,
    borderColor: colors.hairline,
    borderRadius: 8,
    borderWidth: 1,
    flex: 1,
    minHeight: 78,
    paddingHorizontal: 15,
    paddingVertical: 12,
  },
  metricValue: {
    ...type.label,
    color: colors.primary,
    fontSize: 38,
    lineHeight: 40,
  },
  metricLabel: {
    ...type.monoCaption,
    color: colors.muted,
    fontSize: 11,
    lineHeight: 14,
  },
  sectionLabel: {
    ...type.label,
    color: colors.muted,
    fontSize: 11,
    letterSpacing: 1,
    lineHeight: 15,
    marginTop: 15,
  },
  activityList: {
    marginTop: 8,
  },
  activityRow: {
    borderBottomColor: 'rgba(42, 42, 42, 0.7)',
    borderBottomWidth: 1,
    flexDirection: 'row',
    gap: 10,
    paddingVertical: 10,
  },
  activityRowLast: {
    borderBottomWidth: 0,
  },
  activityDot: {
    borderRadius: 3,
    height: 7,
    marginTop: 7,
    width: 7,
  },
  activityCopy: {
    flex: 1,
    gap: 1,
  },
  activityTitle: {
    ...type.label,
    color: colors.ink,
    fontSize: 12,
    lineHeight: 16,
  },
  activityDetail: {
    ...type.monoCaption,
    color: colors.muted,
    fontSize: 10,
    lineHeight: 14,
  },
});
