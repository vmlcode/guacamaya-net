import {
  JetBrainsMono_400Regular,
  JetBrainsMono_300Light,
  JetBrainsMono_700Bold,
  useFonts,
} from '@expo-google-fonts/jetbrains-mono';
import { useState } from 'react';
import { Platform, StatusBar, StyleSheet, View } from 'react-native';

import { ActionModeContent } from './src/components/ActionModeContent';
import { AppHeader } from './src/components/AppHeader';
import { ConnectionStatus } from './src/components/ConnectionStatus';
import { EmergencyActionStack } from './src/components/EmergencyActionStack';
import { colors } from './src/theme';

type ActionMode = 'sos' | 'search';

function AppShell() {
  const [activeMode, setActiveMode] = useState<ActionMode>('sos');
  const [isSosSending, setIsSosSending] = useState(false);
  const [fontsLoaded] = useFonts({
    JetBrainsMono_300Light,
    JetBrainsMono_400Regular,
    JetBrainsMono_700Bold,
  });

  if (!fontsLoaded) {
    return <View style={styles.loadingScreen} />;
  }

  const topInset = Platform.OS === 'android' ? StatusBar.currentHeight ?? 0 : 0;

  return (
    <View style={[styles.screen, { paddingTop: topInset }]}>
      <StatusBar barStyle="light-content" backgroundColor={colors.canvas} translucent={false} />
      <AppHeader isListening={activeMode === 'search'} />
      <View style={styles.content}>
        <View style={styles.topControls}>
          <ConnectionStatus />
          <EmergencyActionStack
            activeMode={activeMode}
            isSosSending={isSosSending}
            onModeChange={setActiveMode}
          />
        </View>
        <View style={styles.modeStage}>
          <ActionModeContent activeMode={activeMode} onSosSendingChange={setIsSosSending} />
        </View>
      </View>
    </View>
  );
}

export default function App() {
  return <AppShell />;
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.canvas,
  },
  loadingScreen: {
    flex: 1,
    backgroundColor: colors.canvas,
  },
  content: {
    flex: 1,
    paddingHorizontal: 6,
    paddingTop: 10,
  },
  topControls: {
    gap: 10,
    position: 'relative',
    zIndex: 3,
  },
  modeStage: {
    flex: 1,
    justifyContent: 'flex-start',
    paddingTop: 20,
    position: 'relative',
    zIndex: 0,
  },
});
