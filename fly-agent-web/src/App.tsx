import { ChatLayout } from './components/layout/ChatLayout'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { SwePipelinePage } from '@/components/swe/SwePipelinePage'
import { AlePipelinePage } from '@/components/ale/AlePipelinePage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<ChatLayout />} />
        <Route path="/swe" element={<SwePipelinePage />} />
        <Route path="/ale" element={<AlePipelinePage />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
