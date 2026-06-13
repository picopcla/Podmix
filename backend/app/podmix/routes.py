from uuid import UUID

from fastapi import APIRouter, HTTPException

from app.podmix.schemas import (
    AnalysisJobResponse,
    AnalysisRequest,
    AnalysisResultResponse,
    SavedTrackEnrichmentRequest,
    SavedTrackEnrichmentResponse,
)
from app.podmix.use_cases import (
    enrich_saved_track,
    get_analysis_job,
    get_analysis_result,
    start_analysis,
)


router = APIRouter()


@router.post("/podcasts/analyze", response_model=AnalysisJobResponse)
async def analyze_podcast(request: AnalysisRequest) -> AnalysisJobResponse:
    return start_analysis.execute(content_kind="podcast", request=request)


@router.post("/livesets/analyze", response_model=AnalysisJobResponse)
async def analyze_liveset(request: AnalysisRequest) -> AnalysisJobResponse:
    return start_analysis.execute(content_kind="liveset", request=request)


@router.get("/jobs/{job_id}", response_model=AnalysisJobResponse)
async def get_job(job_id: UUID) -> AnalysisJobResponse:
    job = get_analysis_job.execute(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="analysis job not found")
    return job


@router.get("/analyses/{analysis_id}", response_model=AnalysisResultResponse)
async def get_analysis(analysis_id: UUID) -> AnalysisResultResponse:
    result = get_analysis_result.execute(analysis_id)
    if result is None:
        raise HTTPException(status_code=404, detail="analysis result not found")
    return result


@router.post(
    "/saved-tracks/enrich",
    response_model=SavedTrackEnrichmentResponse,
)
async def enrich_track(
    request: SavedTrackEnrichmentRequest,
) -> SavedTrackEnrichmentResponse:
    return enrich_saved_track.execute(request)
