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

export interface RoutePolyline {
  readonly routeId: string;
  readonly directionId: string | null | undefined;
  readonly points: readonly { readonly latitude: number; readonly longitude: number }[];
  readonly color: string;
  readonly strokeWidth: number;
  readonly opacity: number;
  readonly isEmphasized: boolean;
}

export interface RouteLegend {
  readonly routeId: string;
  readonly label: string;
  readonly color: string;
  readonly textColor: string;
  readonly status: string;
  readonly focused: boolean;
  readonly canRetry: boolean;
}

export interface RouteChoice {
  readonly id: string;
  readonly shortName: string;
  readonly name: string;
  readonly direction: string;
  readonly color: string;
  readonly textColor: string;
  readonly selected: boolean;
}

export interface RoutesState {
  readonly cityId: string | null | undefined;
  readonly cityName: string;
  readonly status: string;
  readonly canRetry: boolean;
  readonly freshness: string | null | undefined;
  readonly searchQuery: string;
  readonly selectedCount: number;
  readonly selectionLimit: number;
  readonly canConfirm: boolean;
  readonly isConfirming: boolean;
  readonly routes: readonly RouteChoice[];
  readonly recentRoutes: readonly RouteChoice[];
}

export interface MapState {
  readonly cityId: string;
  readonly cityName: string;
  readonly canChangeCity: boolean;
  readonly canOpenRoutes: boolean;
  readonly routePolylines: readonly RoutePolyline[];
  readonly routePolylineRevision: string;
  readonly routeLegends: readonly RouteLegend[];
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
  focusRoute(routeId: string): void;
  retryRoute(routeId: string): void;
  removeRoute(routeId: string): void;
  dispose(): void;
}

export interface RoutesEntry {
  subscribe(onState: (state: RoutesState) => void): void;
  setSearch(query: string): void;
  toggleRoute(id: string): void;
  confirm(): void;
  cancel(): void;
  retry(): void;
  dispose(): void;
}

export interface TransitRuntime {
  start(onDestination: (destination: string) => void, onCityState: (state: CityState) => void): void;
  selectCity(id: string): void;
  confirmCity(): void;
  retryCities(): void;
  openCitySelection(): void;
  openMap(): MapEntry;
  openRouteSelection(): void;
  openRoutes(): RoutesEntry;
  dispose(): void;
}
