import { MaterialIcons } from '@expo/vector-icons';
import type { ComponentProps } from 'react';
import { Pressable, StyleSheet } from 'react-native';

import { colors } from '../theme';

type MaterialIconName = ComponentProps<typeof MaterialIcons>['name'];

type IconButtonProps = {
  name: MaterialIconName;
};

export function IconButton({ name }: IconButtonProps) {
  return (
    <Pressable accessibilityRole="button" style={styles.iconButton}>
      <MaterialIcons name={name} size={27} color={colors.muted} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  iconButton: {
    alignItems: 'center',
    height: 44,
    justifyContent: 'center',
    width: 44,
  },
});
