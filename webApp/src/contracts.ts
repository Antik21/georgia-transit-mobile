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
  readonly animated: boolean;
}

export interface StopPinRouteBadge {
  readonly label: string;
  readonly color: string;
  readonly textColor: string;
  readonly y: number;
}

export interface StopPin {
  readonly imageId: string;
  readonly color: string;
  readonly glyphColor: string;
  readonly width: number;
  readonly height: number;
  readonly bodyTop: number;
  readonly bodyScale: number;
  readonly cardTop: number;
  readonly cardWidth: number;
  readonly cardHeight: number;
  readonly cardCornerRadius: number;
  readonly badgeX: number;
  readonly badgeWidth: number;
  readonly badgeCornerRadius: number;
  readonly badges: readonly StopPinRouteBadge[];
}

export interface MapStopPin {
  readonly id: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly label: string;
  readonly pin: StopPin;
  readonly scale: number;
  readonly selected: boolean;
  readonly highlighted: boolean;
  readonly outsideSelectedRoutes: boolean;
  readonly representsGroup: boolean;
}

export interface MapStopCluster {
  readonly id: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly count: number;
  readonly label: string;
}

export interface MapStopLayer {
  readonly revision: string;
  readonly stops: readonly MapStopPin[];
  readonly clusters: readonly MapStopCluster[];
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

export interface VehicleItem {
  readonly id: string;
  readonly routeId: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly bearing: number | null | undefined;
  readonly positionKind: string;
  readonly label: string;
  readonly color: string;
  readonly textColor: string;
  readonly stale: boolean;
}

export interface VehicleRouteSummary {
  readonly routeId: string;
  readonly label: string;
  readonly vehicleCount: number;
  readonly status: string;
}

export interface LocationStatus {
  readonly permission: string;
  readonly precise: boolean;
  readonly locating: boolean;
  readonly failure: string | null | undefined;
  readonly actionable: boolean;
}

export interface UserLocation {
  readonly latitude: number;
  readonly longitude: number;
  readonly accuracyMeters: number;
  readonly showDot: boolean;
  readonly accuracyRing: readonly { readonly latitude: number; readonly longitude: number }[];
}

export interface LocationCommand {
  readonly id: string;
  readonly kind: string;
  readonly isRefresh: boolean;
}

export interface VehicleLayer {
  readonly revision: string;
  readonly badgeRevision: string;
  readonly vehicles: readonly VehicleItem[];
}

export interface RouteChoice {
  readonly id: string;
  readonly shortName: string;
  readonly name: string;
  readonly direction: string;
  readonly color: string;
  readonly textColor: string;
  readonly selected: boolean;
  readonly mode: string;
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
  readonly vehicleStatus: string;
  readonly vehicleRoutes: readonly VehicleRouteSummary[];
  readonly location: LocationStatus | null | undefined;
  readonly userLocation: UserLocation | null | undefined;
  readonly stopLayer: MapStopLayer | null | undefined;
}

export interface PlannerPoint {
  readonly latitude: number;
  readonly longitude: number;
}

export interface PlannerEndpoint {
  readonly kind: string;
  readonly point: PlannerPoint | null | undefined;
}

export interface PlannerLeg {
  readonly kind: string;
  readonly minutes: number;
  readonly role: string | null | undefined;
  readonly meters: number | null | undefined;
  readonly approximate: boolean;
  readonly routeId: string | null | undefined;
  readonly routeLabel: string | null | undefined;
  readonly routeColor: string | null | undefined;
  readonly routeTextColor: string | null | undefined;
  readonly stopCount: number | null | undefined;
  readonly waitMinutes: number | null | undefined;
  readonly waitLive: boolean;
}

export interface PlannerOption {
  readonly id: string;
  readonly totalMinutes: number;
  readonly arrivalEpochMillis: number;
  readonly transfers: number;
  readonly walkingMeters: number | null | undefined;
  readonly timing: string | null | undefined;
  readonly selected: boolean;
  readonly legs: readonly PlannerLeg[];
}

export interface PlannerRoute {
  readonly revision: string;
  readonly lines: readonly { readonly kind: string; readonly color: string; readonly points: readonly PlannerPoint[] }[];
  readonly origin: PlannerPoint | null | undefined;
  readonly destination: PlannerPoint | null | undefined;
}

export interface PlannerState {
  readonly open: boolean;
  readonly status: string;
  readonly pin: PlannerPoint | null | undefined;
  readonly origin: PlannerEndpoint;
  readonly destination: PlannerEndpoint;
  readonly mode: string;
  readonly side: string | null | undefined;
  readonly reason: string | null | undefined;
  readonly retryable: boolean;
  readonly refreshing: boolean;
  readonly stale: boolean;
  readonly walkMeters: number | null | undefined;
  readonly walkMinutes: number | null | undefined;
  readonly options: readonly PlannerOption[];
  readonly route: PlannerRoute;
}

export interface PlannerEntry {
  subscribe(onState: (state: PlannerState) => void): void;
  longPressMap(latitude: number, longitude: number): void;
  routeHere(): void;
  routeFromHere(): void;
  dismissPin(): void;
  useMyLocationFor(side: string): void;
  pickPointFor(side: string): void;
  swap(): void;
  clear(): void;
  selectOption(id: string): void;
  setMode(mode: string): void;
  retry(): void;
  close(): void;
}

export interface MapEntry {
  readonly planner: PlannerEntry;
  subscribe(onState: (state: MapState) => void): void;
  subscribeVehicles(onVehicles: (layer: VehicleLayer) => void): void;
  selectVehicle(vehicleId: string, revision: string): void;
  subscribeLocationCommands(onCommand: (command: LocationCommand) => void): void;
  myLocation(): void;
  refreshLocation(): void;
  locationPermission(state: string, precise: boolean, canRetry: boolean): void;
  locationFix(requestId: string, latitude: number, longitude: number, accuracyMeters: number, capturedAt: number): void;
  locationFailed(requestId: string, failure: string): void;
  locationCancelled(requestId: string): void;
  setVisible(visible: boolean): void;
  selectStop(stopId: string, sourceRevision: string): void;
  tapStop(stopId: string, sourceRevision: string): void;
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
