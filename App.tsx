import React, {useState, useEffect} from 'react';
import {
  View,
  TextInput,
  StyleSheet,
  PermissionsAndroid,
  Platform,
  Alert,
  SafeAreaView,
  Text,
  ScrollView,
  TouchableOpacity,
  Image,
} from 'react-native';
import {launchImageLibrary} from 'react-native-image-picker';
import ARTextView from './ARTextView';
import ARImageView from './ARImageView';

// AR View Types
const AR_VIEW_TYPES = {
  NONE: 'none',
  TEXT: 'text',
  IMAGE: 'image',
};

export default function App() {
  // State Management
  const [text, setText] = useState('Hello AR');
  const [currentView, setCurrentView] = useState(AR_VIEW_TYPES.NONE);
  const [hasPermission, setHasPermission] = useState(false);

  // Image State
  const [imageUrl, setImageUrl] = useState(
    'https://via.placeholder.com/600x800.png',
  );
  const [selectedImageUri, setSelectedImageUri] = useState<string | null>(null);
  const [imageSource, setImageSource] = useState<'gallery' | 'url'>('gallery');

  // Request camera permission on mount
  useEffect(() => {
    requestCameraPermission();
  }, []);

  // Cleanup when switching views
  useEffect(() => {
    return () => {
      console.log('AR View cleanup');
    };
  }, [currentView]);

  const requestCameraPermission = async () => {
    if (Platform.OS === 'android') {
      try {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.CAMERA,
          {
            title: 'Camera Permission',
            message: 'This app needs camera access for AR features',
            buttonNeutral: 'Ask Me Later',
            buttonNegative: 'Cancel',
            buttonPositive: 'OK',
          },
        );
        setHasPermission(granted === PermissionsAndroid.RESULTS.GRANTED);
        if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
          Alert.alert(
            'Permission Denied',
            'Camera permission is required for AR features',
          );
        }
      } catch (err) {
        console.warn('Permission request error:', err);
      }
    } else {
      setHasPermission(true);
    }
  };

  const requestStoragePermission = async () => {
    if (Platform.OS === 'android') {
      try {
        const granted = await PermissionsAndroid.request(
          Platform.Version >= 33
            ? PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES
            : PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE,
          {
            title: 'Storage Permission',
            message: 'This app needs access to your photos',
            buttonNeutral: 'Ask Me Later',
            buttonNegative: 'Cancel',
            buttonPositive: 'OK',
          },
        );
        return granted === PermissionsAndroid.RESULTS.GRANTED;
      } catch (err) {
        console.warn('Storage permission error:', err);
        return false;
      }
    }
    return true;
  };

  const handlePickImage = async () => {
    const hasStoragePermission = await requestStoragePermission();

    if (!hasStoragePermission) {
      Alert.alert(
        'Permission Required',
        'Please grant storage permission to select images',
      );
      return;
    }

    launchImageLibrary(
      {
        mediaType: 'photo',
        quality: 1,
        selectionLimit: 1,
      },
      response => {
        if (response.didCancel) {
          console.log('User cancelled image picker');
        } else if (response.errorCode) {
          console.log('ImagePicker Error: ', response.errorMessage);
          Alert.alert(
            'Error',
            'Failed to pick image: ' + response.errorMessage,
          );
        } else if (response.assets && response.assets.length > 0) {
          const asset = response.assets[0];
          setSelectedImageUri(asset.uri || null);
          setImageSource('gallery');
          console.log('Selected image URI:', asset.uri);
        }
      },
    );
  };

  const handleShowAR = (viewType: string) => {
    if (hasPermission) {
      setCurrentView(viewType);
    } else {
      Alert.alert(
        'Permission Required',
        'Please grant camera permission first',
        [
          {text: 'Cancel', style: 'cancel'},
          {text: 'Grant Permission', onPress: requestCameraPermission},
        ],
      );
    }
  };

  const handleBackPress = () => {
    setCurrentView(AR_VIEW_TYPES.NONE);
  };

  // Render AR View based on current selection
  const renderARView = () => {
    switch (currentView) {
      case AR_VIEW_TYPES.TEXT:
        return (
          <ARTextView
            text={text}
            style={styles.arView}
            onBack={handleBackPress}
          />
        );

      case AR_VIEW_TYPES.IMAGE:
        return (
          <ARImageView
            imageSource={imageSource === 'gallery' ? selectedImageUri : null}
            imageUrl={imageSource === 'url' ? imageUrl : null}
            style={styles.arView}
            onBack={handleBackPress}
          />
        );

      default:
        return null;
    }
  };

  // Main Menu UI
  if (currentView === AR_VIEW_TYPES.NONE) {
    return (
      <SafeAreaView style={styles.container}>
        <ScrollView contentContainerStyle={styles.scrollContainer}>
          <Text style={styles.title}>AR Demo App</Text>

          {/* Text AR Section */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>Text in AR</Text>
            <TextInput
              style={styles.input}
              value={text}
              onChangeText={setText}
              placeholder="Enter AR text"
              placeholderTextColor="#999"
            />
            <TouchableOpacity
              style={styles.button}
              onPress={() => handleShowAR(AR_VIEW_TYPES.TEXT)}>
              <Text style={styles.buttonText}>Show Text in AR</Text>
            </TouchableOpacity>
          </View>

          {/* Image AR Section */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>Image in AR</Text>

            {/* Image Source Selector */}
            <View style={styles.sourceSelector}>
              <TouchableOpacity
                style={[
                  styles.sourcePill,
                  imageSource === 'gallery' && styles.sourcePillActive,
                ]}
                onPress={() => setImageSource('gallery')}>
                <Text
                  style={[
                    styles.sourcePillText,
                    imageSource === 'gallery' && styles.sourcePillTextActive,
                  ]}>
                  🖼️ Gallery
                </Text>
              </TouchableOpacity>

              <TouchableOpacity
                style={[
                  styles.sourcePill,
                  imageSource === 'url' && styles.sourcePillActive,
                ]}
                onPress={() => setImageSource('url')}>
                <Text
                  style={[
                    styles.sourcePillText,
                    imageSource === 'url' && styles.sourcePillTextActive,
                  ]}>
                  🌐 URL
                </Text>
              </TouchableOpacity>
            </View>

            {/* Gallery Picker */}
            {imageSource === 'gallery' && (
              <View>
                <TouchableOpacity
                  style={[styles.button, styles.buttonSecondary]}
                  onPress={handlePickImage}>
                  <Text style={styles.buttonText}>
                    📷 Pick Image from Gallery
                  </Text>
                </TouchableOpacity>

                {selectedImageUri && (
                  <View style={styles.imagePreviewContainer}>
                    <Image
                      source={{uri: selectedImageUri}}
                      style={styles.imagePreview}
                      resizeMode="cover"
                    />
                    <Text style={styles.imageInfoText} numberOfLines={1}>
                      ✓ Image selected
                    </Text>
                  </View>
                )}
              </View>
            )}

            {/* URL Input */}
            {imageSource === 'url' && (
              <TextInput
                style={styles.input}
                value={imageUrl}
                onChangeText={setImageUrl}
                placeholder="Enter image URL"
                placeholderTextColor="#999"
              />
            )}

            {/* Show in AR Button */}
            <TouchableOpacity
              style={[
                styles.button,
                imageSource === 'gallery' &&
                  !selectedImageUri &&
                  styles.buttonDisabled,
              ]}
              onPress={() => handleShowAR(AR_VIEW_TYPES.IMAGE)}
              disabled={imageSource === 'gallery' && !selectedImageUri}>
              <Text style={styles.buttonText}>Show Image in AR</Text>
            </TouchableOpacity>
          </View>

          {/* Permission Status */}
          <View style={styles.permissionStatus}>
            <Text
              style={
                hasPermission
                  ? styles.permissionGranted
                  : styles.permissionDenied
              }>
              Camera Permission: {hasPermission ? '✓ Granted' : '✗ Denied'}
            </Text>
          </View>
        </ScrollView>
      </SafeAreaView>
    );
  }

  // AR View UI
  return <View style={styles.container}>{renderARView()}</View>;
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  scrollContainer: {
    padding: 20,
    paddingTop: 40,
  },
  title: {
    fontSize: 32,
    fontWeight: 'bold',
    color: '#333',
    marginBottom: 30,
    textAlign: 'center',
  },
  section: {
    backgroundColor: 'white',
    borderRadius: 12,
    padding: 20,
    marginBottom: 20,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  sectionTitle: {
    fontSize: 20,
    fontWeight: '600',
    color: '#333',
    marginBottom: 15,
  },
  sourceSelector: {
    flexDirection: 'row',
    marginBottom: 16,
    gap: 8,
  },
  sourcePill: {
    flex: 1,
    paddingVertical: 10,
    paddingHorizontal: 12,
    borderRadius: 20,
    backgroundColor: '#E5E5EA',
    alignItems: 'center',
  },
  sourcePillActive: {
    backgroundColor: '#007AFF',
  },
  sourcePillText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#666',
  },
  sourcePillTextActive: {
    color: '#FFF',
  },
  input: {
    height: 50,
    borderColor: '#ddd',
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 15,
    fontSize: 16,
    marginBottom: 12,
    backgroundColor: '#fafafa',
    color: '#333',
  },
  button: {
    backgroundColor: '#007AFF',
    paddingVertical: 15,
    borderRadius: 8,
    alignItems: 'center',
    marginTop: 8,
  },
  buttonSecondary: {
    backgroundColor: '#5856D6',
  },
  buttonDisabled: {
    backgroundColor: '#C7C7CC',
  },
  buttonText: {
    color: 'white',
    fontSize: 16,
    fontWeight: '600',
  },
  imagePreviewContainer: {
    marginVertical: 12,
    alignItems: 'center',
  },
  imagePreview: {
    width: '100%',
    height: 200,
    borderRadius: 12,
    marginBottom: 8,
    backgroundColor: '#f0f0f0',
  },
  imageInfoText: {
    fontSize: 14,
    color: '#666',
    fontWeight: '500',
  },
  permissionStatus: {
    marginTop: 20,
    padding: 15,
    backgroundColor: 'white',
    borderRadius: 8,
    alignItems: 'center',
  },
  permissionGranted: {
    color: '#34C759',
    fontSize: 16,
    fontWeight: '600',
  },
  permissionDenied: {
    color: '#FF3B30',
    fontSize: 16,
    fontWeight: '600',
  },
  arView: {
    flex: 1,
  },
});
