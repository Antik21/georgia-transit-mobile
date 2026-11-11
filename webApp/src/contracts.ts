export interface Attribution {
  readonly label: string;
  readonly url: string;
}

export interface CityItem {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly experimental: boolean;
  readonly attribution: readonly Attribution[];
}

export interface CityState {
  readonly status: string;
  readonly cities: readonly CityItem[];
  readonly selectedId: string | null | undefined;
  readonly canContinue: boolean;
}

export interface StopItem {
  readonly id: string;
  readonly name: string;
  readonly sourceRevision: string;
  readonly latitude: number | null | undefined;
  readonly longitude: number | null | undefined;
}

export interface ArrivalItem {
  readonly route: string | null | undefined;
  readonly headsign: string;
  readonly timeKind: string;
  readonly minutes: number | null | undefined;
  readonly source: string;
}

export interface ArrivalsState {
  readonly stopId: string;
  readonly stopName: string;
  readonly status: string;
  readonly stale: boolean;
  readonly refreshing: boolean;
  readonly rows: readonly ArrivalItem[];
}

export interface MapCamera {
  readonly latitude: number;
  readonly longitude: number;
  readonly zoom: number;
  readonly revision: string;
}

export interface MapState {
  readonly cityId: string;
  readonly cityName: string;
  readonly canChangeCity: boolean;
  readonly content: string;
  readonly stops: readonly StopItem[];
  readonly arrivals: ArrivalsState | null | undefined;
  readonly camera: MapCamera | null | undefined;
  readonly styleUrl: string | null | undefined;
  readonly attribution: readonly Attribution[];
}

export interface MapEntry {
  subscribe(onState: (state: MapState) => void): void;
  setVisible(visible: boolean): void;
  selectStop(stopId: string, sourceRevision: string): void;
  updateViewport(latitude: number, longitude: number, radiusMeters: number, zoom: number): void;
  dismissArrivals(): void;
  retryArrivals(): void;
  retryNearby(): void;
  dispose(): void;
}

export interface TransitRuntime {
  start(onDestination: (destination: string) => void, onCityState: (state: CityState) => void): void;
  selectCity(id: string): void;
  confirmCity(): void;
  retryCities(): void;
  openCitySelection(): void;
  openMap(): MapEntry;
  dispose(): void;
}
