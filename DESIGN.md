# Architecture

```mermaid
classDiagram
  class MainActivity
  class MainViewModel
  class StartMockUseCase
  class StopMockUseCase
  class MockLocationService
  class MockLocationEngine
  class FusedMockLocationEngine
  class CoordinateTransformer
  class FavoriteRepository
  class SpoofingDatabase
  class MockStatusStore

  MainActivity --> MainViewModel
  MainViewModel --> StartMockUseCase
  MainViewModel --> StopMockUseCase
  MainViewModel --> FavoriteRepository
  MainViewModel --> MockStatusStore
  StartMockUseCase --> MockLocationService
  StopMockUseCase --> MockLocationService
  MockLocationService --> MockLocationEngine
  MockLocationEngine <|.. FusedMockLocationEngine
  MockLocationService --> CoordinateTransformer
  FavoriteRepository --> SpoofingDatabase
```

## Acceptance checklist
- App cold start target <= 3s (device dependent)
- Map tap response <= 200ms
- Mock injection interval 800ms, first injection target <= 1s
- Stop simulation returns `MockStatus.Idle`
- Permissions and developer-options guidance shown when unavailable
