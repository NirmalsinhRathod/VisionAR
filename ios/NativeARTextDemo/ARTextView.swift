import UIKit
import ARKit
import SceneKit

class ARTextView: UIView, ARSCNViewDelegate {
  
  // MARK: - Properties
  var arView: ARSCNView!
  var textNodes: [(node: SCNNode, rotation: Float)] = []
  var selectedNodeIndex: Int = -1
  var panStartX: CGFloat = 0
  var initialRotation: Float = 0
  var isPanning: Bool = false
  
  private let movementThreshold: CGFloat = 15
  private let hitTestRadius: CGFloat = 150
  private let rotationSensitivity: Float = 0.008
  
  // State tracking
  private var arSessionReady = false
  private var textRendererReady = false
  private var planeDetected = false
  
  @objc var text: String = "Hello AR" {
    didSet {
      updateAllTextNodes()
    }
  }
  
  // MARK: - Initialization
  override init(frame: CGRect) {
    super.init(frame: frame)
    setupARView()
    setupGestures()
    emitAREvent(type: "AR_INITIALIZING", message: "Initializing AR session")
  }
  
  required init?(coder: NSCoder) {
    fatalError("init(coder:) has not been implemented")
  }
  
  // MARK: - Setup
  private func setupARView() {
    arView = ARSCNView(frame: bounds)
    arView.delegate = self
    arView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    arView.automaticallyUpdatesLighting = true
    addSubview(arView)
    
    // Configure AR session
    let configuration = ARWorldTrackingConfiguration()
    configuration.planeDetection = [.horizontal]
    configuration.isLightEstimationEnabled = true
    
    arView.session.run(configuration)
    
    // Mark session as ready
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
      self?.arSessionReady = true
      self?.textRendererReady = true
      self?.emitAREvent(type: "AR_SESSION_READY", message: "AR session initialized - move phone to detect surfaces")
      self?.emitAREvent(type: "TEXT_RENDERER_READY", message: "Text renderer initialized")
    }
  }
  
  private func setupGestures() {
    // Tap gesture for placing text
    let tapGesture = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
    arView.addGestureRecognizer(tapGesture)
    
    // Pan gesture for rotation
    let panGesture = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
    arView.addGestureRecognizer(panGesture)
  }
  
  // MARK: - Gesture Handlers
  @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
    let location = gesture.location(in: arView)
    
    // Check if we have prerequisites
    guard arSessionReady && textRendererReady else {
      emitAREvent(type: "PLACEMENT_BLOCKED", message: "AR not ready yet")
      return
    }
    
    guard planeDetected else {
      emitAREvent(type: "PLACEMENT_BLOCKED", message: "No surface detected, keep scanning")
      return
    }
    
    // Perform hit test for plane
    let hitTestResults = arView.hitTest(location, types: [.existingPlaneUsingExtent, .estimatedHorizontalPlane])
    
    if let hitResult = hitTestResults.first {
      placeText(at: hitResult)
    }
  }
  
  @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
    let location = gesture.location(in: arView)
    
    switch gesture.state {
    case .began:
      panStartX = location.x
      selectedNodeIndex = findClosestNode(at: location)
      
      if selectedNodeIndex >= 0 {
        initialRotation = textNodes[selectedNodeIndex].rotation
        emitAREvent(type: "TEXT_SELECTED", message: "Text selected for rotation")
      }
      
    case .changed:
      if selectedNodeIndex >= 0 {
        let deltaX = location.x - panStartX
        let distance = abs(deltaX)
        
        // Enter panning mode if movement exceeds threshold
        if !isPanning && distance > movementThreshold {
          isPanning = true
          emitAREvent(type: "TEXT_ROTATING", message: "Rotating text")
        }
        
        // Apply rotation
        if isPanning {
          let rotationDelta = Float(deltaX) * rotationSensitivity
          let newRotation = initialRotation + rotationDelta
          textNodes[selectedNodeIndex].rotation = newRotation
          textNodes[selectedNodeIndex].node.eulerAngles.y = newRotation
        }
      }
      
    case .ended, .cancelled:
      if isPanning && selectedNodeIndex >= 0 {
        emitAREvent(type: "TEXT_UPDATED", message: "Text rotation completed")
      }
      isPanning = false
      selectedNodeIndex = -1
      
    default:
      break
    }
  }
  
  // MARK: - AR Operations
  private func placeText(at hitResult: ARHitTestResult) {
    // Create text geometry
    let textGeometry = SCNText(string: text, extrusionDepth: 2.0)
    textGeometry.font = UIFont.boldSystemFont(ofSize: 24)
    textGeometry.flatness = 0.1
    
    // Apply materials
    let material = SCNMaterial()
    material.diffuse.contents = UIColor.white
    material.specular.contents = UIColor.white
    material.isDoubleSided = false
    textGeometry.materials = [material]
    
    // Create node
    let textNode = SCNNode(geometry: textGeometry)
    
    // Center the text
    let (min, max) = textGeometry.boundingBox
    let width = max.x - min.x
    textNode.pivot = SCNMatrix4MakeTranslation(width / 2, 0, 0)
    
    // Scale down to appropriate size
    let scale: Float = 0.01
    textNode.scale = SCNVector3(scale, scale, scale)
    
    // Position at hit result
    let transform = hitResult.worldTransform
    let position = SCNVector3(
      transform.columns.3.x,
      transform.columns.3.y + 0.05, // Slightly above surface
      transform.columns.3.z
    )
    textNode.position = position
    
    // Add to scene
    arView.scene.rootNode.addChildNode(textNode)
    textNodes.append((node: textNode, rotation: 0))
    
    emitAREvent(type: "TEXT_PLACED", message: "Text placed successfully")
  }
  
  private func findClosestNode(at location: CGPoint) -> Int {
    guard !textNodes.isEmpty else { return -1 }
    
    var closestIndex = -1
    var closestDistance: CGFloat = .infinity
    
    for (index, item) in textNodes.enumerated() {
      let node = item.node
      let projection = arView.projectPoint(node.position)
      let screenPoint = CGPoint(x: CGFloat(projection.x), y: CGFloat(projection.y))
      
      let dx = screenPoint.x - location.x
      let dy = screenPoint.y - location.y
      let distance = sqrt(dx * dx + dy * dy)
      
      if distance < hitTestRadius && distance < closestDistance {
        closestDistance = distance
        closestIndex = index
      }
    }
    
    return closestIndex
  }
  
  private func updateAllTextNodes() {
    for item in textNodes {
      if let textGeometry = item.node.geometry as? SCNText {
        textGeometry.string = text
      }
    }
  }
  
  // MARK: - Event Emitter
  private func emitAREvent(type: String, message: String) {
    // Find the bridge and emit event
    if let bridge = findReactBridge() {
      let params: [String: Any] = [
        "type": type,
        "message": message,
        "arSessionReady": arSessionReady,
        "textRendererReady": textRendererReady,
        "planeDetected": planeDetected
      ]
      
      bridge.enqueueJSCall("RCTDeviceEventEmitter", method: "emit", args: ["onARTextStateChange", params], completion: nil)
    }
  }
  
  private func findReactBridge() -> RCTBridge? {
    var responder: UIResponder? = self
    while responder != nil {
      if let bridge = responder as? RCTBridge {
        return bridge
      }
      responder = responder?.next
    }
    
    // Alternative: search through app delegate
    if let appDelegate = UIApplication.shared.delegate as? NSObject,
       let bridge = appDelegate.value(forKey: "bridge") as? RCTBridge {
      return bridge
    }
    
    return nil
  }
  
  // MARK: - ARSCNViewDelegate
  func renderer(_ renderer: SCNSceneRenderer, didAdd node: SCNNode, for anchor: ARAnchor) {
    guard anchor is ARPlaneAnchor else { return }
    
    if !planeDetected {
      planeDetected = true
      DispatchQueue.main.async { [weak self] in
        self?.emitAREvent(type: "PLANE_DETECTED", message: "Surface detected - tap to place text")
      }
    }
  }
  
  func session(_ session: ARSession, didFailWithError error: Error) {
    DispatchQueue.main.async { [weak self] in
      self?.emitAREvent(type: "AR_ERROR", message: "AR Error: \(error.localizedDescription)")
    }
  }
  
  func sessionWasInterrupted(_ session: ARSession) {
    DispatchQueue.main.async { [weak self] in
      self?.emitAREvent(type: "AR_ERROR", message: "AR session was interrupted")
    }
  }
  
  func sessionInterruptionEnded(_ session: ARSession) {
    // Restart session
    let configuration = ARWorldTrackingConfiguration()
    configuration.planeDetection = [.horizontal]
    arView.session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
  }
  
  // MARK: - Cleanup
  override func removeFromSuperview() {
    arView.session.pause()
    super.removeFromSuperview()
  }
  
  deinit {
    arView?.session.pause()
  }
}

